package com.violet.wifidogauthenticator.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Restarts the foreground service after device reboot if it was running before.
 *
 * FIX: Added null checks on context and intent to prevent NPE when some ROM
 *      delivers a malformed broadcast (rare but observed on custom AOSP forks).
 * FIX: Wrapped startForegroundService / startService in try/catch to prevent
 *      SecurityException or IllegalStateException from crashing the receiver,
 *      which would prevent the broadcast from completing cleanly.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // FIX: Guard null context and intent
        if (context == null || intent == null) return;

        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            SharedPreferences prefs = context.getSharedPreferences(
                    AuthForegroundService.PREFS_NAME, Context.MODE_PRIVATE);

            if (prefs.getBoolean(AuthForegroundService.KEY_RUNNING, false)) {
                Intent serviceIntent = new Intent(context, AuthForegroundService.class);
                serviceIntent.setAction(AuthForegroundService.ACTION_START);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Exception e) {
                    // FIX: On Android 12+ with exact-alarm / background restrictions,
                    // startForegroundService can throw. Log and swallow gracefully.
                    android.util.Log.e("BootReceiver",
                            "Failed to restart service on boot: " + e.getMessage());
                }
            }
        }
    }
}
