package so.oskarshop.sms.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import so.oskarshop.sms.service.SmsForwarderService;
import so.oskarshop.sms.storage.SmsSettings;

/**
 * 24/7 Watchdog Receiver that ensures OskarSMS is never killed, frozen, or interrupted.
 * Regularly wakes up and restarts the persistent Foreground Service if the OS or OEM terminates it.
 */
public class WatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "OskarWatchdog";
    public static final String ACTION_WATCHDOG_PING = "so.oskarshop.sms.WATCHDOG_PING";
    private static final long WATCHDOG_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "Watchdog triggered with action: " + action);

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OskarSMS:WatchdogWakeLock");
            wakeLock.acquire(10000); // 10s max
        }

        try {
            SmsSettings settings = new SmsSettings(context);
            if (settings.isEnabled()) {
                SmsForwarderService.startListening(context);
            }
            // Always reschedule next ping to keep watchdog perpetual
            scheduleWatchdog(context);
        } catch (Exception e) {
            Log.e(TAG, "Error in WatchdogReceiver: " + e.getMessage());
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    public static void scheduleWatchdog(Context context) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) return;

            Intent intent = new Intent(context, WatchdogReceiver.class);
            intent.setAction(ACTION_WATCHDOG_PING);

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                8888,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            long triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
            Log.d(TAG, "Watchdog scheduled for " + (WATCHDOG_INTERVAL_MS / 1000) + "s from now");
        } catch (SecurityException se) {
            Log.w(TAG, "Exact alarm permission restricted; falling back to inexact alarm: " + se.getMessage());
            try {
                AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (alarmManager != null) {
                    Intent intent = new Intent(context, WatchdogReceiver.class);
                    intent.setAction(ACTION_WATCHDOG_PING);
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        context,
                        8888,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    );
                    alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + WATCHDOG_INTERVAL_MS, pendingIntent);
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule watchdog: " + e.getMessage());
        }
    }
}
