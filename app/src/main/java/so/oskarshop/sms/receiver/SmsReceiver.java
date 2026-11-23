package so.oskarshop.sms.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import so.oskarshop.sms.service.SmsForwarderService;
import so.oskarshop.sms.storage.SmsSettings;
import so.oskarshop.sms.util.SmsInboxTracker;

/**
 * Universal High-Priority SMS Broadcast Receiver (Priority 2147483647).
 * Captures 100% of incoming SMS from ANY sender and ANY number without restriction.
 */
public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "OskarSmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action) &&
            !"android.provider.Telephony.WAP_PUSH_RECEIVED".equals(action)) {
            return;
        }

        SmsSettings settings = new SmsSettings(context);
        if (!settings.isLoggedIn()) {
            Log.d(TAG, "OskarSMS: Dropped incoming SMS because administrator is not logged in.");
            return;
        }
        if (!settings.isEnabled()) {
            Log.d(TAG, "OskarSMS is disabled by admin in settings, skipping incoming SMS.");
            return;
        }

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) {
            return;
        }

        String sender = messages[0].getDisplayOriginatingAddress();
        if (sender == null || sender.trim().isEmpty()) {
            sender = "Unknown";
        }

        StringBuilder fullBodyBuilder = new StringBuilder();
        long timestamp = messages[0].getTimestampMillis();
        if (timestamp <= 0) {
            timestamp = System.currentTimeMillis();
        }

        for (SmsMessage msg : messages) {
            if (msg != null && msg.getDisplayMessageBody() != null) {
                fullBodyBuilder.append(msg.getDisplayMessageBody());
            }
        }
        String fullBody = fullBodyBuilder.toString();

        if (fullBody.trim().isEmpty()) {
            return;
        }

        // Anti-SMS Injection Security Guard: Strictly drop SMS from personal mobile numbers or non-carrier senders
        if (!SmsInboxTracker.isLegitimateCarrierSender(sender, fullBody)) {
            Log.w(TAG, "Anti-SMS-Injection: Blocked incoming SMS from unauthorized/personal sender: [" + sender + "]");
            return;
        }

        Log.i(TAG, "Legitimate carrier SMS captured from sender: [" + sender + "]: " + fullBody);

        // Deduplication: prevent duplicate processing between Broadcast and ContentObserver
        String fingerprint = SmsInboxTracker.computeFingerprint(sender, fullBody, timestamp);
        if (!SmsInboxTracker.markIfNew(context, fingerprint)) {
            Log.d(TAG, "Duplicate broadcast SMS skipped: " + fingerprint);
            return;
        }

        // Forward authentic carrier payment SMS to SmsForwarderService
        SmsForwarderService.startForwarding(context, sender, fullBody);
    }
}
