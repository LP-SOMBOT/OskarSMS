package so.oskarshop.sms.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import so.oskarshop.sms.service.SmsForwarderService;
import so.oskarshop.sms.storage.SmsSettings;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "OskarSmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }

        SmsSettings settings = new SmsSettings(context);
        if (!settings.isEnabled()) {
            Log.d(TAG, "OskarSMS is disabled by admin, skipping incoming SMS.");
            return;
        }

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) {
            return;
        }

        String sender = messages[0].getDisplayOriginatingAddress();
        if (sender == null) sender = "192";

        StringBuilder fullBodyBuilder = new StringBuilder();
        for (SmsMessage msg : messages) {
            if (msg != null && msg.getDisplayMessageBody() != null) {
                fullBodyBuilder.append(msg.getDisplayMessageBody());
            }
        }
        String fullBody = fullBodyBuilder.toString();

        Log.d(TAG, "Incoming SMS from " + sender + ": " + fullBody);

        // Check if message is EVC Plus (or if forwarding all)
        if (settings.isEvcOnly()) {
            boolean isEvc = isEvcPlusPayment(sender, fullBody);
            if (!isEvc) {
                Log.d(TAG, "Ignoring non-EVC SMS from: " + sender);
                return;
            }
        }

        // Forward to Foreground Service to guarantee network transmission with WakeLock
        SmsForwarderService.startForwarding(context, sender, fullBody);
    }

    private boolean isEvcPlusPayment(String sender, String body) {
        String s = sender.trim().toLowerCase();
        String b = body.trim().toLowerCase();

        // 1. Hormuud shortcode 192 or EVCPlus
        if (s.contains("192") || s.contains("evcplus") || s.contains("hormuud")) {
            return true;
        }

        // 2. Body keywords
        if (b.contains("evcplus") || b.contains("evc-plus") || b.contains("evc plus")) {
            return true;
        }

        // 3. Somali payment phrase: "waxaad ... ka heshay" or "ka heshay"
        if (b.contains("ka heshay") || b.contains("ka heshay")) {
            return true;
        }

        // 4. Any dollar amount with Somali mobile phone
        if (b.contains("$") && (b.contains("061") || b.contains("25261") || b.contains("068"))) {
            return true;
        }

        return false;
    }
}
