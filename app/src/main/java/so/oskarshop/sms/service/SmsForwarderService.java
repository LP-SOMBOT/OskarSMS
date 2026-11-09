package so.oskarshop.sms.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import so.oskarshop.sms.MainActivity;
import so.oskarshop.sms.R;
import so.oskarshop.sms.model.SmsLogEntry;
import so.oskarshop.sms.network.OskarApiClient;
import so.oskarshop.sms.storage.SmsLogStorage;
import so.oskarshop.sms.storage.SmsSettings;

public class SmsForwarderService extends Service {
    private static final String TAG = "OskarSmsService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "oskar_sms_foreground_channel";
    private static final String PAYMENT_CHANNEL_ID = "oskar_sms_payment_channel";

    public static final String ACTION_START_LISTENING = "so.oskarshop.sms.START_LISTENING";
    public static final String ACTION_FORWARD_SMS = "so.oskarshop.sms.FORWARD_SMS";
    public static final String ACTION_NEW_LOG = "so.oskarshop.sms.NEW_LOG";

    public static final String EXTRA_SENDER = "extra_sender";
    public static final String EXTRA_BODY = "extra_body";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private OskarApiClient apiClient;
    private SmsSettings settings;
    private SmsLogStorage logStorage;
    private PowerManager.WakeLock wakeLock;

    public static void startListening(Context context) {
        Intent intent = new Intent(context, SmsForwarderService.class);
        intent.setAction(ACTION_START_LISTENING);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void startForwarding(Context context, String sender, String body) {
        Intent intent = new Intent(context, SmsForwarderService.class);
        intent.setAction(ACTION_FORWARD_SMS);
        intent.putExtra(EXTRA_SENDER, sender);
        intent.putExtra(EXTRA_BODY, body);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        apiClient = new OskarApiClient();
        settings = new SmsSettings(this);
        logStorage = new SmsLogStorage(this);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OskarSMS:ServiceWakeLock");
        }

        createNotificationChannels();
        startForeground(NOTIFICATION_ID, buildForegroundNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_FORWARD_SMS.equals(action)) {
                String sender = intent.getStringExtra(EXTRA_SENDER);
                String body = intent.getStringExtra(EXTRA_BODY);
                if (sender != null && body != null) {
                    processSms(sender, body);
                }
            }
        }
        return START_STICKY;
    }

    private void processSms(String sender, String body) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(30000); // Hold for 30s max
        }

        executor.execute(() -> {
            try {
                Log.i(TAG, "Forwarding SMS to OskarShop backend...");

                OskarApiClient.ForwardResult result = apiClient.forwardSms(
                    settings.getServerUrl(),
                    settings.getFallbackUrl(),
                    settings.getSecretToken(),
                    sender,
                    body
                );

                settings.incrementTotalForwarded();

                String status = "FAILED";
                if (result.success) {
                    if ("matched".equalsIgnoreCase(result.status)) {
                        status = "MATCHED";
                        settings.incrementTotalMatched();
                        showPaymentMatchedNotification(result.matchedOrderId, result.amount, result.phone);
                    } else if ("unmatched".equalsIgnoreCase(result.status)) {
                        status = "RECEIVED";
                    } else {
                        status = result.status.toUpperCase();
                    }
                }

                SmsLogEntry entry = new SmsLogEntry(
                    sender,
                    body,
                    result.amount,
                    result.phone,
                    status,
                    result.matchedOrderId,
                    result.message
                );

                logStorage.addLog(entry);

                // Notify UI to refresh in real-time
                Intent broadcast = new Intent(ACTION_NEW_LOG);
                sendBroadcast(broadcast);

            } catch (Exception e) {
                Log.e(TAG, "Error in processSms worker: " + e.getMessage(), e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        });
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                // Foreground listener channel (low priority, silent)
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription(getString(R.string.channel_desc));
                nm.createNotificationChannel(channel);

                // Payment verified channel (high priority, heads-up)
                NotificationChannel paymentChannel = new NotificationChannel(
                    PAYMENT_CHANNEL_ID,
                    getString(R.string.payment_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                );
                paymentChannel.setDescription(getString(R.string.payment_channel_desc));
                nm.createNotificationChannel(paymentChannel);
            }
        }
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OskarSMS Active")
            .setContentText("Monitoring & Auto-Verifying EVC Plus Payments 24/7")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void showPaymentMatchedNotification(String orderId, Double amount, String phone) {
        String amtStr = amount != null ? "$" + String.format("%.2f", amount) : "Payment";
        String body = amtStr + " from " + (phone != null ? phone : "customer") + " fulfilled Order #" + (orderId != null ? orderId : "");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, PAYMENT_CHANNEL_ID)
            .setContentTitle("✅ EVC Plus Payment Verified!")
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify((int) System.currentTimeMillis(), builder.build());
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
