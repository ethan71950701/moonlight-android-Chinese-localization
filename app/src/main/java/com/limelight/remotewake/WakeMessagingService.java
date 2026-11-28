package com.limelight.remotewake;

import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.limelight.LimeLog;
import com.limelight.nvstream.wol.WakeOnLanSender;

import java.io.IOException;
import java.util.Map;

/**
 * Receiver side: when a high priority FCM data message with action=wake arrives,
 * broadcast a Wake-on-LAN packet on the local network.
 *
 * FCM wakes the app only for the duration of onMessageReceived(), so no foreground
 * service is needed. FCM calls this on a background thread.
 */
public class WakeMessagingService extends FirebaseMessagingService {
    private static final long MIN_INTERVAL_MS = 3000;
    private static final int ROUNDS = 3;
    private static final long ROUND_GAP_MS = 300;

    private static final Object LOCK = new Object();
    private static long lastWakeElapsed = -MIN_INTERVAL_MS;

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        if (!"wake".equals(data.get("action"))) {
            return;
        }

        SharedPreferences prefs = RemoteWakeConfig.prefs(this);
        if (!prefs.getBoolean(RemoteWakeConfig.KEY_RECEIVER_ENABLED, false)) {
            LimeLog.info("RemoteWake: wake message ignored, receiver is disabled");
            return;
        }

        String mac = RemoteWakeConfig.normalizeMac(prefs.getString(RemoteWakeConfig.KEY_MAC, ""));
        if (mac == null) {
            LimeLog.warning("RemoteWake: wake message ignored, MAC address is not configured");
            return;
        }

        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastWakeElapsed < MIN_INTERVAL_MS) {
                LimeLog.info("RemoteWake: duplicate wake message ignored");
                return;
            }
            lastWakeElapsed = now;
        }

        // UDP is unreliable, so send a few rounds of the magic packet.
        for (int i = 0; i < ROUNDS; i++) {
            try {
                WakeOnLanSender.sendWolPacket(mac);
                LimeLog.info("RemoteWake: sent Wake-on-LAN packet to " + mac);
            } catch (IOException e) {
                LimeLog.warning("RemoteWake: failed to send Wake-on-LAN packet: " + e.getMessage());
            }

            if (i < ROUNDS - 1) {
                try {
                    Thread.sleep(ROUND_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        RemoteWakeConfig.prefs(this).edit()
                .putString(RemoteWakeConfig.KEY_OWN_TOKEN, token)
                .apply();
    }
}
