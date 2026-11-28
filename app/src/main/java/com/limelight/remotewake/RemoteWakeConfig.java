package com.limelight.remotewake;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.Locale;

/**
 * Preference keys and small helpers shared by the remote wake receiver and sender.
 */
public final class RemoteWakeConfig {
    public static final String KEY_RECEIVER_ENABLED = "checkbox_remote_wake_receiver";
    public static final String KEY_MAC = "remote_wake_mac";
    public static final String KEY_OWN_TOKEN = "remote_wake_own_token";
    public static final String KEY_TARGET_TOKEN = "remote_wake_target_token";
    public static final String KEY_SERVICE_ACCOUNT = "remote_wake_service_account_json";
    public static final String KEY_TOKEN_DIALOG_SHOWN = "remote_wake_token_dialog_shown";

    private RemoteWakeConfig() {}

    public static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /**
     * Normalizes a MAC address to the lower-case "aa:bb:cc:dd:ee:ff" form.
     * Accepts ':' or '-' separators, or no separators at all.
     *
     * @return the normalized address, or null if the input is not a valid MAC address
     */
    public static String normalizeMac(String raw) {
        if (raw == null) {
            return null;
        }
        String hex = raw.trim().replace(":", "").replace("-", "");
        if (hex.length() != 12) {
            return null;
        }
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) {
                return null;
            }
        }
        hex = hex.toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    public static String getTargetToken(Context context) {
        String token = prefs(context).getString(KEY_TARGET_TOKEN, "");
        return token == null ? "" : token.trim();
    }

    /** Returns the stored service account, or null if none is stored or it can't be parsed. */
    public static ServiceAccount getServiceAccount(Context context) {
        String json = prefs(context).getString(KEY_SERVICE_ACCOUNT, "");
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return ServiceAccount.parse(json);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isSenderConfigured(Context context) {
        return !getTargetToken(context).isEmpty() && getServiceAccount(context) != null;
    }
}
