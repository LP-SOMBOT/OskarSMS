package so.oskarshop.sms.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import so.oskarshop.sms.MainActivity;
import so.oskarshop.sms.R;
import so.oskarshop.sms.model.OrderItem;
import so.oskarshop.sms.model.SmsLogEntry;
import so.oskarshop.sms.network.OskarApiClient;
import so.oskarshop.sms.receiver.WatchdogReceiver;
import so.oskarshop.sms.storage.SmsLogStorage;
import so.oskarshop.sms.storage.SmsSettings;
import so.oskarshop.sms.util.SmsInboxTracker;

/**
 * Persistent 24/7 Foreground Service with Dual-Capture Technology:
 * 1. BroadcastReceiver (instant capture)
 * 2. SQLite ContentObserver (instant inbox capture)
 * 3. 10s Periodic Inbox Reconciliation Poller (fail-safe)
 * 4. Watchdog & Auto-Restart Shield (anti-kill / anti-interrupt)
 */
public class SmsForwarderService extends Service {
    private static final String TAG = "OskarSmsService";
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "oskar_sms_foreground_channel";
    private static final String PAYMENT_CHANNEL_ID = "oskar_sms_payment_channel";
    public static final String ORDERS_CHANNEL_ID = "oskar_new_orders_channel";

    public static final String ACTION_START_LISTENING = "so.oskarshop.sms.START_LISTENING";
    public static final String ACTION_FORWARD_SMS = "so.oskarshop.sms.FORWARD_SMS";
    public static final String ACTION_TEST_ORDER = "so.oskarshop.sms.TEST_ORDER";
    public static final String ACTION_NEW_LOG = "so.oskarshop.sms.NEW_LOG";
    public static final String ACTION_NEW_ORDER = "so.oskarshop.sms.NEW_ORDER";

    public static final String EXTRA_SENDER = "extra_sender";
    public static final String EXTRA_BODY = "extra_body";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService pollerExecutor;
    private OskarApiClient apiClient;
    private SmsSettings settings;
    private SmsLogStorage logStorage;
    private PowerManager.WakeLock wakeLock;
    private ContentObserver smsContentObserver;

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

    public static void testOrderNotification(Context context) {
        Intent intent = new Intent(context, SmsForwarderService.class);
        intent.setAction(ACTION_TEST_ORDER);
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
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification());

        // 1. Register SMS ContentObserver for instant SQLite inbox capture
        registerSmsContentObserver();

        // 2. Start background pollers (order alerts + periodic inbox reconciliation)
        startBackgroundPollers();

        // 3. Schedule 24/7 anti-kill watchdog
        WatchdogReceiver.scheduleWatchdog(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        WatchdogReceiver.scheduleWatchdog(this);

        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_FORWARD_SMS.equals(action)) {
                String sender = intent.getStringExtra(EXTRA_SENDER);
                String body = intent.getStringExtra(EXTRA_BODY);
                if (sender != null && body != null) {
                    processSms(sender, body);
                }
            } else if (ACTION_TEST_ORDER.equals(action)) {
                OrderItem testOrder = new OrderItem(
                    "iibinta-test",
                    "110 Free Fire Diamonds",
                    1.19,
                    "0613982172",
                    "Macmiil Tijaabo",
                    "pending",
                    System.currentTimeMillis()
                );
                showNewOrderNotification(testOrder);
            }
        }
        return START_STICKY;
    }

    private void registerSmsContentObserver() {
        if (smsContentObserver != null) return;
        try {
            smsContentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    Log.d(TAG, "ContentObserver notified SMS database change: " + uri);
                    executor.execute(() -> scanAndForwardInboxMessages());
                }
            };
            getContentResolver().registerContentObserver(
                Uri.parse("content://sms"),
                true,
                smsContentObserver
            );
            Log.i(TAG, "Dual-Capture: Registered ContentObserver on content://sms");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register ContentObserver: " + e.getMessage());
        }
    }

    private void startBackgroundPollers() {
        if (pollerExecutor != null && !pollerExecutor.isShutdown()) {
            return;
        }

        if (settings.getLastCheckedOrderTime() <= 0) {
            settings.setLastCheckedOrderTime(System.currentTimeMillis() - 30000);
        }

        pollerExecutor = Executors.newScheduledThreadPool(2);

        // 1. Order alert poller: checks every 6 seconds
        pollerExecutor.scheduleWithFixedDelay(this::pollOrdersWorker, 2, 6, TimeUnit.SECONDS);

        // 2. Fail-safe inbox sweep poller: checks every 10 seconds for any missed SMS
        pollerExecutor.scheduleWithFixedDelay(this::scanAndForwardInboxMessages, 4, 10, TimeUnit.SECONDS);
    }

    /**
     * Sweeps recent inbox messages from the phone database and forwards any new messages.
     * Captures 100% of SMS from any sender and any number.
     */
    private void scanAndForwardInboxMessages() {
        if (!settings.isEnabled()) return;
        try {
            long window = System.currentTimeMillis() - 15 * 60 * 1000; // last 15 minutes
            List<SmsInboxTracker.RawSms> messages = SmsInboxTracker.queryRecentInbox(this, window);
            for (SmsInboxTracker.RawSms msg : messages) {
                if (msg.body == null || msg.body.trim().isEmpty()) continue;
                String fingerprint = SmsInboxTracker.computeFingerprint(msg.address, msg.body, msg.date);
                if (SmsInboxTracker.markIfNew(this, fingerprint)) {
                    Log.i(TAG, "Dual-Capture detected unforwarded SMS in inbox from [" + msg.address + "]: " + msg.body);
                    processSms(msg.address, msg.body);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in scanAndForwardInboxMessages: " + e.getMessage());
        }
    }

    private void pollOrdersWorker() {
        if (!settings.isEnabled() || !settings.isOrderNotificationsEnabled()) {
            return;
        }

        try {
            long lastChecked = settings.getLastCheckedOrderTime();
            if (lastChecked <= 0) {
                lastChecked = System.currentTimeMillis() - 30000;
                settings.setLastCheckedOrderTime(lastChecked);
            }

            List<OrderItem> newOrders = apiClient.fetchNewOrders(
                settings.getOrdersUrl(),
                settings.getOrdersFallbackUrl(),
                settings.getSecretToken(),
                lastChecked
            );

            if (newOrders != null && !newOrders.isEmpty()) {
                long highestTimestamp = lastChecked;

                for (OrderItem order : newOrders) {
                    if (order.createdAt > lastChecked) {
                        showNewOrderNotification(order);
                        settings.incrementTotalOrdersNotified();
                        if (order.createdAt > highestTimestamp) {
                            highestTimestamp = order.createdAt;
                        }
                    }
                }

                if (highestTimestamp > lastChecked) {
                    settings.setLastCheckedOrderTime(highestTimestamp);
                    sendBroadcast(new Intent(ACTION_NEW_ORDER));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in pollOrdersWorker: " + e.getMessage());
        }
    }

    public void processSms(String sender, String body) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(30000); // Hold for 30s max
        }

        executor.execute(() -> {
            try {
                Log.i(TAG, "Forwarding captured SMS to OskarShop production backend: sender=" + sender);

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
                    } else if ("ignored".equalsIgnoreCase(result.status)) {
                        status = "IGNORED";
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
                // 1. Foreground listener channel (low priority, persistent 24/7)
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription(getString(R.string.channel_desc));
                nm.createNotificationChannel(channel);

                // 2. Payment verified channel (high priority, heads-up)
                NotificationChannel paymentChannel = new NotificationChannel(
                    PAYMENT_CHANNEL_ID,
                    getString(R.string.payment_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                );
                paymentChannel.setDescription(getString(R.string.payment_channel_desc));
                paymentChannel.enableVibration(true);
                paymentChannel.setVibrationPattern(new long[]{0, 250, 150, 250});
                nm.createNotificationChannel(paymentChannel);

                // 3. New Orders channel (high priority, heads-up with sound & vibration)
                NotificationChannel ordersChannel = new NotificationChannel(
                    ORDERS_CHANNEL_ID,
                    "New Orders (Dalabyada Cusub)",
                    NotificationManager.IMPORTANCE_HIGH
                );
                ordersChannel.setDescription("Alerts admin when customers place new orders on oskarshop.so");
                ordersChannel.enableLights(true);
                ordersChannel.setLightColor(Color.CYAN);
                ordersChannel.enableVibration(true);
                ordersChannel.setVibrationPattern(new long[]{0, 300, 150, 300, 150, 300});

                Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .build();
                ordersChannel.setSound(soundUri, audioAttributes);

                nm.createNotificationChannel(ordersChannel);
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

        Bitmap oskarLogo = BitmapFactory.decodeResource(getResources(), R.drawable.ic_oskar_logo);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OskarSMS Active (Dual-Capture)")
            .setContentText("Capturing 100% of SMS & Alerting Orders 24/7")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(oskarLogo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void showPaymentMatchedNotification(String orderId, Double amount, String phone) {
        String amtStr = amount != null ? "$" + String.format("%.2f", amount) : "Payment";
        String body = amtStr + " from " + (phone != null ? phone : "customer") + " fulfilled Order #" + (orderId != null ? orderId : "");
        Bitmap oskarLogo = BitmapFactory.decodeResource(getResources(), R.drawable.ic_oskar_logo);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, PAYMENT_CHANNEL_ID)
            .setContentTitle("✅ EVC Plus Payment Verified!")
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(oskarLogo)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify((int) System.currentTimeMillis(), builder.build());
        }
    }

    public void showNewOrderNotification(OrderItem order) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(5000);
        }

        String title = "🛒 Dalab Cusub (New Order)!";
        String amtStr = String.format("$%.2f", order.amount);
        String summary = "Order #" + order.id + " • " + amtStr + " (" + order.title + ")";

        String detailedText = "Dalab cusub ayaa la soo dhiibtay!\n"
            + "Dalabka: #" + order.id + "\n"
            + "Qiimaha: " + amtStr + "\n"
            + "Macmiilka: " + order.customerPhone + " (" + order.customerName + ")\n"
            + "Alaabta: " + order.title;

        Intent adminWebIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://oskarshop.so/admin"));
        PendingIntent adminPendingIntent = PendingIntent.getActivity(
            this,
            (int) System.currentTimeMillis(),
            adminWebIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent appIntent = new Intent(this, MainActivity.class);
        PendingIntent appPendingIntent = PendingIntent.getActivity(
            this,
            (int) (System.currentTimeMillis() + 1),
            appIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Bitmap oskarLogo = BitmapFactory.decodeResource(getResources(), R.drawable.ic_oskar_logo);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ORDERS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(detailedText))
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(oskarLogo)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(adminPendingIntent)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .addAction(R.drawable.ic_notification, "Fur Admin", adminPendingIntent)
            .addAction(R.drawable.ic_notification, "App-ka", appPendingIntent);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify((int) (System.currentTimeMillis() % 100000), builder.build());
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.w(TAG, "onTaskRemoved: User swiped app from Recents. Auto-resurrecting service immediately...");

        Intent restartIntent = new Intent(getApplicationContext(), SmsForwarderService.class);
        restartIntent.setAction(ACTION_START_LISTENING);
        PendingIntent restartPendingIntent = PendingIntent.getService(
            getApplicationContext(),
            9999,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                restartPendingIntent
            );
        }

        try {
            ContextCompat.startForegroundService(getApplicationContext(), restartIntent);
        } catch (Exception ignored) {}
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (smsContentObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(smsContentObserver);
            } catch (Exception ignored) {}
        }
        executor.shutdown();
        if (pollerExecutor != null) {
            pollerExecutor.shutdown();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        WatchdogReceiver.scheduleWatchdog(this);
    }
}
