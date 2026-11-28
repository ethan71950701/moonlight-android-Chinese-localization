package com.limelight.remotewake;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.limelight.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * UI glue for remote wake: the settings screen entries and the button on the main screen.
 */
public final class RemoteWakeUi {
    private static final int MAX_JSON_BYTES = 64 * 1024;

    private RemoteWakeUi() {}

    private interface TokenCallback {
        void onToken(String token);
        void onError(String message);
    }

    /** Fetches this device's FCM token. Must be called on the main thread. */
    private static void fetchToken(Context context, TokenCallback callback) {
        final Context appContext = context.getApplicationContext();
        try {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                callback.onError(appContext.getString(R.string.remote_wake_firebase_not_configured));
                return;
            }
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    String token = task.getResult();
                    RemoteWakeConfig.prefs(appContext).edit()
                            .putString(RemoteWakeConfig.KEY_OWN_TOKEN, token)
                            .apply();
                    callback.onToken(token);
                } else {
                    Exception e = task.getException();
                    callback.onError(appContext.getString(R.string.remote_wake_token_failed,
                            e != null ? String.valueOf(e.getMessage()) : "unknown"));
                }
            });
        } catch (Throwable t) {
            callback.onError(appContext.getString(R.string.remote_wake_firebase_not_configured));
        }
    }

    private static void copyToClipboard(Context context, String text) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("FCM token", text));
        }
    }

    /** Sends the wake message on a background thread and reports the outcome on the main thread. */
    private static void sendWakeAsync(Context context, String mac, Runnable onStart, Runnable onFinish) {
        final Context appContext = context.getApplicationContext();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

        if (onStart != null) {
            onStart.run();
        }
        Toast.makeText(appContext, R.string.remote_wake_sending, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            FcmSender.Result result = FcmSender.sendWake(appContext, mac);
            main.post(() -> {
                Toast.makeText(appContext, describe(appContext, result), Toast.LENGTH_LONG).show();
                if (onFinish != null) {
                    onFinish.run();
                }
            });
        }, "RemoteWakeSender").start();
    }

    private static String describe(Context context, FcmSender.Result result) {
        switch (result.status) {
            case OK:
                return context.getString(R.string.remote_wake_sent);
            case NOT_CONFIGURED:
                return context.getString(R.string.remote_wake_failed,
                        context.getString(R.string.remote_wake_err_not_configured));
            case NETWORK:
                return context.getString(R.string.remote_wake_failed,
                        context.getString(R.string.remote_wake_err_network, result.detail));
            case AUTH:
                return context.getString(R.string.remote_wake_failed,
                        context.getString(R.string.remote_wake_err_auth, result.detail));
            case INVALID_TOKEN:
                return context.getString(R.string.remote_wake_failed,
                        context.getString(R.string.remote_wake_err_invalid_token, result.detail));
            default:
                return context.getString(R.string.remote_wake_failed, result.detail);
        }
    }

    // ------------------------------------------------------------------
    // Main screen
    // ------------------------------------------------------------------

    public static void setupMainScreen(final Activity activity) {
        final ImageButton button = activity.findViewById(R.id.remoteWakeButton);
        if (button == null) {
            return;
        }

        button.setOnClickListener(v -> sendWakeAsync(activity, null,
                () -> button.setEnabled(false),
                () -> button.setEnabled(true)));

        refreshMainScreen(activity);
        maybeShowFirstLaunchTokenDialog(activity);
    }

    /** True when this device can send remote wake messages (token and service account are set). */
    public static boolean canSend(Context context) {
        return RemoteWakeConfig.isSenderConfigured(context);
    }

    /** Wakes one specific computer (from the PC list menu) through the receiver. */
    public static void sendWakeForComputer(Activity activity, String macAddress) {
        String mac = RemoteWakeConfig.normalizeMac(macAddress);
        if (mac == null) {
            Toast.makeText(activity, R.string.wol_no_mac, Toast.LENGTH_SHORT).show();
            return;
        }
        sendWakeAsync(activity, mac, null, null);
    }

    /** Shows the wake button only when the sender side is fully configured. */
    public static void refreshMainScreen(Activity activity) {
        ImageButton button = activity.findViewById(R.id.remoteWakeButton);
        if (button != null) {
            button.setVisibility(RemoteWakeConfig.isSenderConfigured(activity) ? View.VISIBLE : View.GONE);
        }
    }

    private static void maybeShowFirstLaunchTokenDialog(final Activity activity) {
        final SharedPreferences prefs = RemoteWakeConfig.prefs(activity);
        if (prefs.getBoolean(RemoteWakeConfig.KEY_TOKEN_DIALOG_SHOWN, false)) {
            return;
        }

        fetchToken(activity, new TokenCallback() {
            @Override
            public void onToken(String token) {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                showTokenDialog(activity, token);
                prefs.edit().putBoolean(RemoteWakeConfig.KEY_TOKEN_DIALOG_SHOWN, true).apply();
            }

            @Override
            public void onError(String message) {
                // Stay silent: Firebase may simply not be set up in this build.
                // The dialog will be tried again on the next launch.
            }
        });
    }

    private static void showTokenDialog(final Activity activity, final String token) {
        TextView text = new TextView(activity);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad / 2, pad, 0);
        text.setText(activity.getString(R.string.dialog_remote_wake_token_message) + "\n\n" + token);
        text.setTextIsSelectable(true);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_remote_wake_token_title)
                .setView(text)
                .setPositiveButton(R.string.remote_wake_copy, (d, w) -> {
                    copyToClipboard(activity, token);
                    Toast.makeText(activity, R.string.remote_wake_token_copied, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.remote_wake_close, null)
                .show();
    }

    // ------------------------------------------------------------------
    // Settings screen
    // ------------------------------------------------------------------

    /** Wires up the "Remote Wake" preference category. Call from the settings fragment's onCreatePreferences. */
    public static void bindSettings(final PreferenceFragmentCompat fragment) {
        final Context context = fragment.requireContext();
        final SharedPreferences prefs = RemoteWakeConfig.prefs(context);

        // Receiver: allow list of MAC addresses (one per line)
        final EditTextPreference macPref = fragment.findPreference(RemoteWakeConfig.KEY_MAC);
        if (macPref != null) {
            macPref.setOnBindEditTextListener(edit -> {
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                edit.setSingleLine(false);
                edit.setMinLines(3);
                edit.setHint("AA:BB:CC:DD:EE:FF");
            });
            macPref.setSummaryProvider(p -> {
                java.util.List<String> macs = RemoteWakeConfig.parseMacList(((EditTextPreference) p).getText());
                return macs.isEmpty()
                        ? context.getString(R.string.remote_wake_unset)
                        : android.text.TextUtils.join("\n", macs);
            });
            macPref.setOnPreferenceChangeListener((p, newValue) -> {
                String text = String.valueOf(newValue);
                String invalid = RemoteWakeConfig.findInvalidMac(text);
                if (invalid != null) {
                    Toast.makeText(context, context.getString(R.string.remote_wake_mac_invalid, invalid),
                            Toast.LENGTH_LONG).show();
                    return false;
                }
                // Store the normalized list instead of what the user typed.
                ((EditTextPreference) p).setText(
                        android.text.TextUtils.join("\n", RemoteWakeConfig.parseMacList(text)));
                return false;
            });
        }

        // Receiver: this device's token
        final Preference ownTokenPref = fragment.findPreference(RemoteWakeConfig.KEY_OWN_TOKEN);
        if (ownTokenPref != null) {
            String savedToken = prefs.getString(RemoteWakeConfig.KEY_OWN_TOKEN, "");
            ownTokenPref.setSummary(savedToken == null || savedToken.isEmpty()
                    ? context.getString(R.string.summary_remote_wake_own_token_tap)
                    : savedToken);
            ownTokenPref.setOnPreferenceClickListener(p -> {
                fetchToken(context, new TokenCallback() {
                    @Override
                    public void onToken(String token) {
                        copyToClipboard(context, token);
                        Toast.makeText(context, R.string.remote_wake_token_copied, Toast.LENGTH_SHORT).show();
                        ownTokenPref.setSummary(token);
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                    }
                });
                return true;
            });
        }

        // Sender: receiver's token
        final EditTextPreference targetPref = fragment.findPreference(RemoteWakeConfig.KEY_TARGET_TOKEN);
        if (targetPref != null) {
            targetPref.setOnBindEditTextListener(edit -> edit.setSingleLine(false));
            targetPref.setSummaryProvider(p -> {
                String token = ((EditTextPreference) p).getText();
                if (token == null || token.trim().isEmpty()) {
                    return context.getString(R.string.remote_wake_unset);
                }
                token = token.trim();
                return token.length() > 20
                        ? token.substring(0, 10) + "…" + token.substring(token.length() - 8)
                        : token;
            });
            targetPref.setOnPreferenceChangeListener((p, newValue) -> {
                // Pasted tokens often carry stray whitespace or line breaks.
                String cleaned = String.valueOf(newValue).replaceAll("\\s", "");
                ((EditTextPreference) p).setText(cleaned);
                return false;
            });
        }

        // Sender: service account JSON
        final Preference accountPref = fragment.findPreference(RemoteWakeConfig.KEY_SERVICE_ACCOUNT);
        final Preference clearPref = fragment.findPreference("remote_wake_clear_service_account");
        final Runnable refreshAccount = () -> {
            ServiceAccount account = RemoteWakeConfig.getServiceAccount(context);
            if (accountPref != null) {
                accountPref.setSummary(account == null
                        ? context.getString(R.string.remote_wake_service_account_unset)
                        : context.getString(R.string.remote_wake_service_account_summary,
                                account.projectId, account.clientEmail));
            }
            if (clearPref != null) {
                clearPref.setEnabled(account != null);
            }
        };
        refreshAccount.run();

        final ActivityResultLauncher<String[]> picker = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) {
                        return;
                    }
                    importServiceAccount(context, prefs, uri);
                    refreshAccount.run();
                });

        if (accountPref != null) {
            accountPref.setOnPreferenceClickListener(p -> {
                picker.launch(new String[] {"application/json", "application/octet-stream", "text/plain", "*/*"});
                return true;
            });
        }
        if (clearPref != null) {
            clearPref.setOnPreferenceClickListener(p -> {
                prefs.edit().remove(RemoteWakeConfig.KEY_SERVICE_ACCOUNT).apply();
                Toast.makeText(context, R.string.remote_wake_service_account_cleared, Toast.LENGTH_SHORT).show();
                refreshAccount.run();
                return true;
            });
        }

        // Sender: test button
        final Preference sendPref = fragment.findPreference("remote_wake_send_now");
        if (sendPref != null) {
            sendPref.setOnPreferenceClickListener(p -> {
                sendWakeAsync(context, null, () -> sendPref.setEnabled(false), () -> sendPref.setEnabled(true));
                return true;
            });
        }
    }

    private static void importServiceAccount(Context context, SharedPreferences prefs, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("cannot open file");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_JSON_BYTES) {
                    throw new IOException("file is too large");
                }
            }
            String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
            ServiceAccount account = ServiceAccount.parse(json);
            prefs.edit().putString(RemoteWakeConfig.KEY_SERVICE_ACCOUNT, json).apply();
            Toast.makeText(context,
                    context.getString(R.string.remote_wake_service_account_imported, account.projectId),
                    Toast.LENGTH_LONG).show();
        } catch (IOException | IllegalArgumentException e) {
            Toast.makeText(context,
                    context.getString(R.string.remote_wake_service_account_invalid, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }
}
