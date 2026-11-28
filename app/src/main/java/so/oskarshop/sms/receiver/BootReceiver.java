package so.oskarshop.sms.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import so.oskarshop.sms.service.SmsForwarderService;
import so.oskarshop.sms.storage.SmsSettings;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "OskarBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Log.d(TAG, "System event received: " + intent.getAction());

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OskarSMS:BootWakeLock");
            wakeLock.acquire(10000);
        }

        try {
            SmsSettings settings = new SmsSettings(context);
            if (settings.isEnabled() && settings.isLoggedIn()) {
                SmsForwarderService.startListening(context);
                WatchdogReceiver.scheduleWatchdog(context);
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }
}
