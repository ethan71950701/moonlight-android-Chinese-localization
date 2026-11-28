package com.limelight.remotewake;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The parts of a Google Cloud service account key file (JSON) that we need
 * in order to call the FCM HTTP v1 API.
 */
public final class ServiceAccount {
    public static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

    public final String projectId;
    public final String clientEmail;
    public final String privateKeyPem;
    public final String tokenUri;

    private ServiceAccount(String projectId, String clientEmail, String privateKeyPem, String tokenUri) {
        this.projectId = projectId;
        this.clientEmail = clientEmail;
        this.privateKeyPem = privateKeyPem;
        this.tokenUri = tokenUri;
    }

    /**
     * @throws IllegalArgumentException if the text is not a usable service account key
     */
    public static ServiceAccount parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("empty file");
        }

        JsonObject obj;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("not a JSON object");
            }
            obj = root.getAsJsonObject();
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            throw new IllegalArgumentException("not valid JSON");
        }

        String type = string(obj, "type");
        if (type != null && !"service_account".equals(type)) {
            throw new IllegalArgumentException("type is not service_account");
        }

        String projectId = require(obj, "project_id");
        String clientEmail = require(obj, "client_email");
        String privateKey = require(obj, "private_key");
        if (!privateKey.contains("BEGIN PRIVATE KEY")) {
            throw new IllegalArgumentException("private_key is not a PKCS#8 PEM key");
        }

        String tokenUri = string(obj, "token_uri");
        if (tokenUri == null || tokenUri.isEmpty()) {
            tokenUri = DEFAULT_TOKEN_URI;
        }

        return new ServiceAccount(projectId, clientEmail, privateKey, tokenUri);
    }

    private static String string(JsonObject obj, String name) {
        JsonElement e = obj.get(name);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        return e.getAsString();
    }

    private static String require(JsonObject obj, String name) {
        String value = string(obj, name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return value;
    }
}
