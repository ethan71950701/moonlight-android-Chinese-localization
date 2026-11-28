package com.limelight.remotewake;

import android.content.Context;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends a high priority FCM data message (action=wake) using the FCM HTTP v1 API,
 * authenticating with a Google service account. Blocking: call from a background thread.
 */
public final class FcmSender {
    public enum Status { OK, NOT_CONFIGURED, NETWORK, AUTH, INVALID_TOKEN, OTHER }

    public static final class Result {
        public final Status status;
        /** Technical detail from the server or the exception (may be empty). */
        public final String detail;

        Result(Status status, String detail) {
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isSuccess() {
            return status == Status.OK;
        }
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build();

    private static final Object TOKEN_LOCK = new Object();
    private static String cachedAccessToken;
    private static String cachedForClientEmail;
    private static long cachedExpiryMillis;

    private FcmSender() {}

    public static Result sendWake(Context context) {
        ServiceAccount account = RemoteWakeConfig.getServiceAccount(context);
        String targetToken = RemoteWakeConfig.getTargetToken(context);
        if (account == null || targetToken.isEmpty()) {
            return new Result(Status.NOT_CONFIGURED, "");
        }
        return sendWake(account, targetToken);
    }

    static Result sendWake(ServiceAccount account, String targetToken) {
        try {
            String accessToken = getAccessToken(account);
            return postMessage(account, accessToken, targetToken);
        } catch (AuthException e) {
            return new Result(Status.AUTH, e.getMessage());
        } catch (GeneralSecurityException e) {
            return new Result(Status.AUTH, "private key: " + e.getMessage());
        } catch (IOException e) {
            return new Result(Status.NETWORK, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static final class AuthException extends IOException {
        AuthException(String message) {
            super(message);
        }
    }

    private static String getAccessToken(ServiceAccount account) throws IOException, GeneralSecurityException {
        synchronized (TOKEN_LOCK) {
            long now = System.currentTimeMillis();
            if (cachedAccessToken != null
                    && account.clientEmail.equals(cachedForClientEmail)
                    && now < cachedExpiryMillis - 60_000) {
                return cachedAccessToken;
            }

            String jwt = JwtSigner.createJwt(account, now / 1000);
            RequestBody body = new FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    .add("assertion", jwt)
                    .build();
            Request request = new Request.Builder().url(account.tokenUri).post(body).build();

            try (Response response = CLIENT.newCall(request).execute()) {
                String text = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new AuthException("HTTP " + response.code() + " " + errorSummary(text));
                }
                JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                JsonElement token = obj.get("access_token");
                if (token == null || !token.isJsonPrimitive()) {
                    throw new AuthException("no access_token in response");
                }
                long expiresIn = obj.has("expires_in") ? obj.get("expires_in").getAsLong() : 3600;
                cachedAccessToken = token.getAsString();
                cachedForClientEmail = account.clientEmail;
                cachedExpiryMillis = now + expiresIn * 1000;
                return cachedAccessToken;
            } catch (RuntimeException e) {
                throw new AuthException("unexpected token response");
            }
        }
    }

    private static Result postMessage(ServiceAccount account, String accessToken, String targetToken) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("action", "wake");
        data.addProperty("ts", String.valueOf(System.currentTimeMillis()));

        JsonObject android = new JsonObject();
        android.addProperty("priority", "HIGH");
        // A stale wake request is worthless: drop it if it can't be delivered quickly.
        android.addProperty("ttl", "60s");

        JsonObject message = new JsonObject();
        message.addProperty("token", targetToken);
        message.add("data", data);
        message.add("android", android);

        JsonObject root = new JsonObject();
        root.add("message", message);

        Request request = new Request.Builder()
                .url("https://fcm.googleapis.com/v1/projects/" + account.projectId + "/messages:send")
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(root.toString(), JSON))
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (response.isSuccessful()) {
                return new Result(Status.OK, "");
            }

            String summary = "HTTP " + response.code() + " " + errorSummary(text);
            int code = response.code();
            if (code == 401) {
                synchronized (TOKEN_LOCK) {
                    cachedAccessToken = null;
                }
                return new Result(Status.AUTH, summary);
            }
            if (code == 403) {
                return new Result(Status.AUTH, summary);
            }
            if (code == 404 || (code == 400 && text.contains("registration token"))
                    || text.contains("UNREGISTERED")) {
                return new Result(Status.INVALID_TOKEN, summary);
            }
            return new Result(Status.OTHER, summary);
        }
    }

    /** Extracts "STATUS: message" from a Google API error body, or a short prefix of the body. */
    static String errorSummary(String body) {
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject();
            if (error.has("error") && error.get("error").isJsonObject()) {
                JsonObject e = error.getAsJsonObject("error");
                String status = e.has("status") ? e.get("status").getAsString() : "";
                String msg = e.has("message") ? e.get("message").getAsString() : "";
                return (status + " " + msg).trim();
            }
            if (error.has("error_description")) {
                return error.get("error").getAsString() + ": " + error.get("error_description").getAsString();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        String trimmed = body == null ? "" : body.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
}
