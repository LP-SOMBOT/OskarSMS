package so.oskarshop.sms;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Color;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import so.oskarshop.sms.model.SmsLogEntry;
import so.oskarshop.sms.network.OskarApiClient;
import so.oskarshop.sms.service.SmsForwarderService;
import so.oskarshop.sms.storage.SmsLogStorage;
import so.oskarshop.sms.storage.SmsSettings;
import so.oskarshop.sms.update.AppUpdateDialog;
import so.oskarshop.sms.update.AppUpdateManager;
import so.oskarshop.sms.update.UpdateInfo;
import so.oskarshop.sms.util.PermissionUtils;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQ_SMS = 101;
    private static final int PERMISSION_REQ_NOTIF = 102;

    private SmsSettings settings;
    private SmsLogStorage logStorage;
    private OskarApiClient apiClient;

    private TextView tvMainStatus;
    private ImageView ivStatusDot;
    private TextView tvAppVersion;
    private MaterialSwitch switchEnable;
    private MaterialSwitch switchOrderNotif;
    private TextView tvTotalForwarded;
    private TextView tvTotalMatched;
    private TextView tvTotalOrdersNotified;
    private Button btnTestOrderAlert;
    private Button btnSmsPermission;
    private Button btnBatteryOptimization;
    private Button btnPingServer;
    private TextView tvEndpointUrl;
    private TextView tvEmptyState;
    private RecyclerView rvLogs;
    private LogAdapter adapter;
    private final List<SmsLogEntry> logList = new ArrayList<>();

    private UpdateInfo availableUpdate = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshLogsAndStats();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new SmsSettings(this);
        logStorage = new SmsLogStorage(this);
        apiClient = new OskarApiClient();

        initViews();
        setupRecyclerView();
        checkPermissionsAndPrompts();
        refreshLogsAndStats();

        // Start persistent background service if enabled and schedule watchdog
        if (settings.isEnabled()) {
            SmsForwarderService.startListening(this);
            so.oskarshop.sms.receiver.WatchdogReceiver.scheduleWatchdog(this);
        }

        // Instantly check for updates on startup without manual action
        checkForUpdatesInstantly();
    }

    private void initViews() {
        tvMainStatus = findViewById(R.id.tvMainStatus);
        ivStatusDot = findViewById(R.id.ivStatusDot);
        tvAppVersion = findViewById(R.id.tvAppVersion);
        switchEnable = findViewById(R.id.switchEnable);
        switchOrderNotif = findViewById(R.id.switchOrderNotif);
        tvTotalForwarded = findViewById(R.id.tvTotalForwarded);
        tvTotalMatched = findViewById(R.id.tvTotalMatched);
        tvTotalOrdersNotified = findViewById(R.id.tvTotalOrdersNotified);
        btnTestOrderAlert = findViewById(R.id.btnTestOrderAlert);
        btnSmsPermission = findViewById(R.id.btnSmsPermission);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        btnPingServer = findViewById(R.id.btnPingServer);
        tvEndpointUrl = findViewById(R.id.tvEndpointUrl);
        tvEmptyState = findViewById(R.id.tvEmptyState);
        rvLogs = findViewById(R.id.rvLogs);

        TextView btnClearLogs = findViewById(R.id.btnClearLogs);
        btnClearLogs.setOnClickListener(v -> {
            logStorage.clearLogs();
            refreshLogsAndStats();
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
        });

        tvEndpointUrl.setText(settings.getServerUrl().replace("https://", ""));
        tvAppVersion.setText("v" + AppUpdateManager.getInstance(this).getCurrentVersionName());
        tvAppVersion.setOnClickListener(v -> checkForUpdatesInstantly());

        // Setup Master Switch
        switchEnable.setChecked(settings.isEnabled());
        updateStatusUi(settings.isEnabled());

        switchEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.setEnabled(isChecked);
            updateStatusUi(isChecked);
            if (isChecked) {
                SmsForwarderService.startListening(this);
                Toast.makeText(this, "OskarSMS Activated: Monitoring EVC Plus & New Orders", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "OskarSMS Paused", Toast.LENGTH_SHORT).show();
            }
        });

        // Setup Order Notifications Switch
        switchOrderNotif.setChecked(settings.isOrderNotificationsEnabled());
        switchOrderNotif.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.setOrderNotificationsEnabled(isChecked);
            Toast.makeText(this, isChecked ? "New order alerts enabled 🔔" : "New order alerts disabled", Toast.LENGTH_SHORT).show();
        });

        // Test Order Alert button
        btnTestOrderAlert.setOnClickListener(v -> {
            SmsForwarderService.testOrderNotification(this);
            Toast.makeText(this, "🔔 Test notification triggered with sound!", Toast.LENGTH_SHORT).show();
        });

        // Permission buttons
        btnSmsPermission.setOnClickListener(v -> requestSmsPermissions());
        btnBatteryOptimization.setOnClickListener(v -> PermissionUtils.requestIgnoreBatteryOptimizations(this));

        // Ping button
        btnPingServer.setOnClickListener(v -> testServerConnection());
    }

    private void updateStatusUi(boolean enabled) {
        if (enabled) {
            tvMainStatus.setText(R.string.status_active);
            tvMainStatus.setTextColor(Color.WHITE);
            if (ivStatusDot != null) ivStatusDot.setImageResource(R.drawable.dot_online);
        } else {
            tvMainStatus.setText(R.string.status_inactive);
            tvMainStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            if (ivStatusDot != null) ivStatusDot.setImageResource(R.drawable.dot_offline);
        }
    }

    private void setupRecyclerView() {
        adapter = new LogAdapter(logList);
        rvLogs.setLayoutManager(new LinearLayoutManager(this));
        rvLogs.setAdapter(adapter);
    }

    private void checkPermissionsAndPrompts() {
        // SMS & Telephony Full Access
        if (PermissionUtils.hasSmsPermissions(this)) {
            btnSmsPermission.setText("✓ Full Access");
            btnSmsPermission.setBackgroundResource(R.drawable.bg_badge_pill_green);
            btnSmsPermission.setTextColor(ContextCompat.getColor(this, R.color.emerald_text));
            btnSmsPermission.setEnabled(false);
        } else {
            btnSmsPermission.setText("⚠️ Grant Access");
            btnSmsPermission.setBackgroundResource(R.drawable.bg_badge_pill_amber);
            btnSmsPermission.setTextColor(ContextCompat.getColor(this, R.color.amber_text));
            btnSmsPermission.setEnabled(true);
            requestSmsPermissions();
        }

        // Battery Optimization
        if (PermissionUtils.isBatteryOptimizationIgnored(this)) {
            btnBatteryOptimization.setText("✓ Unrestricted");
            btnBatteryOptimization.setBackgroundResource(R.drawable.bg_badge_pill_green);
            btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.emerald_text));
            btnBatteryOptimization.setEnabled(false);
        } else {
            btnBatteryOptimization.setText("⚠️ Fix Setting");
            btnBatteryOptimization.setBackgroundResource(R.drawable.bg_badge_pill_amber);
            btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.amber_text));
            btnBatteryOptimization.setEnabled(true);
        }

        // Notifications for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!PermissionUtils.hasNotificationPermission(this)) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQ_NOTIF);
            }
        }
    }

    private void checkForUpdatesInstantly() {
        AppUpdateManager.getInstance(this).checkForUpdate(new AppUpdateManager.CheckCallback() {
            @Override
            public void onUpdateAvailable(UpdateInfo info) {
                availableUpdate = info;
                tvAppVersion.setText("v" + info.getVersionName() + " 🚀");
                tvAppVersion.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.primary));

                // Immediately pop up the full-screen non-dismissible update screen
                AppUpdateDialog.showUpdate(getSupportFragmentManager(), info);
            }

            @Override
            public void onNoUpdate() {
                // Already latest version
            }

            @Override
            public void onError(String message) {
                // Silent
            }
        });
    }

    private void requestSmsPermissions() {
        ActivityCompat.requestPermissions(
            this,
            PermissionUtils.REQUIRED_PERMISSIONS,
            PERMISSION_REQ_SMS
        );
    }

    private void testServerConnection() {
        btnPingServer.setEnabled(false);
        btnPingServer.setText("Pinging...");

        new Thread(() -> {
            boolean success = apiClient.pingServer(settings.getServerUrl(), settings.getSecretToken());
            settings.setLastPingResult(success);

            mainHandler.post(() -> {
                btnPingServer.setEnabled(true);
                btnPingServer.setText("📶 Test Ping");
                if (success) {
                    Toast.makeText(this, "✅ Connected to oskarshop.so production!", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "⚠️ Could not reach server. Check internet connection.", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void refreshLogsAndStats() {
        tvTotalForwarded.setText(String.valueOf(settings.getTotalForwarded()));
        tvTotalMatched.setText(String.valueOf(settings.getTotalMatched()));
        tvTotalOrdersNotified.setText(String.valueOf(settings.getTotalOrdersNotified()));

        List<SmsLogEntry> storedLogs = logStorage.getLogs();
        logList.clear();
        logList.addAll(storedLogs);
        adapter.notifyDataSetChanged();

        if (logList.isEmpty()) {
            tvEmptyState.setVisibility(View.VISIBLE);
            rvLogs.setVisibility(View.GONE);
        } else {
            tvEmptyState.setVisibility(View.GONE);
            rvLogs.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        checkForUpdatesInstantly();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissionsAndPrompts();
        refreshLogsAndStats();
        checkForUpdatesInstantly();

        IntentFilter filter = new IntentFilter();
        filter.addAction(SmsForwarderService.ACTION_NEW_LOG);
        filter.addAction(SmsForwarderService.ACTION_NEW_ORDER);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        checkPermissionsAndPrompts();
        if (requestCode == AppUpdateDialog.REQ_INSTALL_PERM) {
            AppUpdateDialog dialog = (AppUpdateDialog) getSupportFragmentManager().findFragmentByTag(AppUpdateDialog.TAG);
            if (dialog != null && dialog.isVisible()) {
                dialog.onPermissionResult();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        checkPermissionsAndPrompts();
        if (requestCode == PERMISSION_REQ_SMS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ SMS Permission Granted", Toast.LENGTH_SHORT).show();
                if (settings.isEnabled()) {
                    SmsForwarderService.startListening(this);
                }
            } else {
                Toast.makeText(this, "⚠️ SMS Permission is required for automatic payment verification", Toast.LENGTH_LONG).show();
            }
        }
    }

    // RecyclerView Adapter
    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {
        private final List<SmsLogEntry> list;

        LogAdapter(List<SmsLogEntry> list) {
            this.list = list;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sms_log, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SmsLogEntry item = list.get(position);

            holder.tvLogTime.setText(item.getFormattedTime());
            holder.tvRawMessage.setText(item.getRawMessage());

            if (item.getAmount() != null && item.getAmount() > 0) {
                holder.tvAmount.setText(String.format(Locale.US, "$%.2f", item.getAmount()));
                holder.tvAmount.setVisibility(View.VISIBLE);
            } else {
                holder.tvAmount.setVisibility(View.GONE);
            }

            if (item.getPayerPhone() != null && !item.getPayerPhone().isEmpty()) {
                holder.tvPhone.setText(item.getPayerPhone());
                holder.tvPhone.setVisibility(View.VISIBLE);
            } else {
                holder.tvPhone.setVisibility(View.GONE);
            }

            String status = item.getStatus() != null ? item.getStatus() : "RECEIVED";
            holder.tvStatusBadge.setText(status);

            if ("MATCHED".equalsIgnoreCase(status)) {
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.emerald));
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_active);
                if (item.getMatchedOrderId() != null && !item.getMatchedOrderId().isEmpty()) {
                    holder.tvMatchedOrder.setText("Order #" + item.getMatchedOrderId());
                    holder.tvMatchedOrder.setVisibility(View.VISIBLE);
                } else {
                    holder.tvMatchedOrder.setVisibility(View.GONE);
                }
            } else if ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.rose));
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_inactive);
                holder.tvMatchedOrder.setVisibility(View.GONE);
            } else {
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.primary_light));
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_inactive);
                holder.tvMatchedOrder.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView tvStatusBadge;
            final TextView tvMatchedOrder;
            final TextView tvLogTime;
            final TextView tvAmount;
            final TextView tvPhone;
            final TextView tvRawMessage;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvStatusBadge = itemView.findViewById(R.id.tvStatusBadge);
                tvMatchedOrder = itemView.findViewById(R.id.tvMatchedOrder);
                tvLogTime = itemView.findViewById(R.id.tvLogTime);
                tvAmount = itemView.findViewById(R.id.tvAmount);
                tvPhone = itemView.findViewById(R.id.tvPhone);
                tvRawMessage = itemView.findViewById(R.id.tvRawMessage);
            }
        }
    }
}
