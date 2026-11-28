package com.limelight.remotewake;

import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.limelight.LimeLog;
import com.limelight.nvstream.wol.WakeOnLanSender;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
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
    private static final Map<String, Long> lastWakeElapsed = new HashMap<>();

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

        List<String> allowed = RemoteWakeConfig.parseMacList(prefs.getString(RemoteWakeConfig.KEY_MAC, ""));
        if (allowed.isEmpty()) {
            LimeLog.warning("RemoteWake: wake message ignored, no MAC address is configured");
            return;
        }

        // No MAC in the message: wake the default (first) computer. Otherwise the
        // requested computer must be on the allow list.
        String requested = data.get("mac");
        final String mac;
        if (requested == null || requested.isEmpty()) {
            mac = allowed.get(0);
        } else {
            mac = RemoteWakeConfig.normalizeMac(requested);
            if (mac == null || !allowed.contains(mac)) {
                LimeLog.warning("RemoteWake: wake message ignored, MAC is not on the allow list");
                return;
            }
        }

        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            Long last = lastWakeElapsed.get(mac);
            if (last != null && now - last < MIN_INTERVAL_MS) {
                LimeLog.info("RemoteWake: duplicate wake message ignored");
                return;
            }
            lastWakeElapsed.put(mac, now);
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
