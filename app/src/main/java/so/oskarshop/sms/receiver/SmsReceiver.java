package so.oskarshop.sms.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import java.util.regex.Pattern;
import so.oskarshop.sms.service.SmsForwarderService;
import so.oskarshop.sms.storage.SmsSettings;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "OskarSmsReceiver";

    // Strictly match outgoing transfers ("ayaad uwareejisay", "u wareejisay", "wareejisay", "bixisay", "ku shubatay", "u dirtay")
    private static final Pattern OUTGOING_PATTERN = Pattern.compile(
        "(u\\s*wareejisay|wareejisay|bixisay|ku\\s*shubatay|u\\s*dirtay)",
        Pattern.CASE_INSENSITIVE
    );

    // Strictly match incoming payment receipt indicator ("ka heshay")
    private static final Pattern INCOMING_PATTERN = Pattern.compile(
        "ka\\s+heshay",
        Pattern.CASE_INSENSITIVE
    );

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

        // Strict filter: only send incoming SMS that contains "ka heshay" and is NOT outgoing ("ayaad uwareejisay")
        if (!isIncomingPaymentSms(fullBody)) {
            Log.d(TAG, "Ignoring SMS: not an incoming payment with 'ka heshay' or is an outgoing transfer.");
            return;
        }

        Log.i(TAG, "Incoming payment SMS verified. Forwarding to foreground service...");
        // Forward to Foreground Service to guarantee network transmission with WakeLock
        SmsForwarderService.startForwarding(context, sender, fullBody);
    }

    /**
     * Determines whether the received SMS is a valid incoming EVC Plus payment receipt.
     * 
     * Rule:
     * 1. Must NOT match outgoing transfers ("ayaad uwareejisay", "u wareejisay", "bixisay", etc.)
     * 2. MUST contain "ka heshay" ("you received from")
     */
    public static boolean isIncomingPaymentSms(String body) {
        if (body == null || body.trim().isEmpty()) {
            return false;
        }

        // 1. Check for outgoing transfer patterns - reject immediately
        if (OUTGOING_PATTERN.matcher(body).find()) {
            return false;
        }

        // 2. Check for incoming payment keyword "ka heshay" - must be present
        if (!INCOMING_PATTERN.matcher(body).find()) {
            return false;
        }

        return true;
    }
}
