package com.limelight.remotewake;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    /**
     * Splits a user supplied list (one MAC per line; commas, semicolons and spaces also work)
     * into normalized, de-duplicated MAC addresses. Invalid entries are skipped.
     */
    public static List<String> parseMacList(String raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String token : raw.split("[\\s,;]+")) {
                String mac = normalizeMac(token);
                if (mac != null) {
                    result.add(mac);
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** Returns the first entry of the list that is not a valid MAC address, or null if all are valid. */
    public static String findInvalidMac(String raw) {
        if (raw == null) {
            return null;
        }
        for (String token : raw.split("[\\s,;]+")) {
            if (!token.isEmpty() && normalizeMac(token) == null) {
                return token;
            }
        }
        return null;
    }

    public static List<String> getAllowedMacs(Context context) {
        return parseMacList(prefs(context).getString(KEY_MAC, ""));
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
