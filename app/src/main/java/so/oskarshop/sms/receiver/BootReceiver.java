package so.oskarshop.sms.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import so.oskarshop.sms.service.SmsForwarderService;
import so.oskarshop.sms.storage.SmsSettings;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "OskarBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Log.d(TAG, "System event received: " + intent.getAction());

        SmsSettings settings = new SmsSettings(context);
        if (settings.isEnabled()) {
            SmsForwarderService.startListening(context);
            WatchdogReceiver.scheduleWatchdog(context);
        }
    }
}
