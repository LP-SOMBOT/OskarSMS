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
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
import java.util.List;
import so.oskarshop.sms.model.SmsLogEntry;
import so.oskarshop.sms.network.OskarApiClient;
import so.oskarshop.sms.service.SmsForwarderService;
import so.oskarshop.sms.storage.SmsLogStorage;
import so.oskarshop.sms.storage.SmsSettings;
import so.oskarshop.sms.util.PermissionUtils;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQ_SMS = 101;
    private static final int PERMISSION_REQ_NOTIF = 102;

    private SmsSettings settings;
    private SmsLogStorage logStorage;
    private OskarApiClient apiClient;

    private TextView tvMainStatus;
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

        // Start persistent background service if enabled
        if (settings.isEnabled()) {
            SmsForwarderService.startListening(this);
        }
    }

    private void initViews() {
        tvMainStatus = findViewById(R.id.tvMainStatus);
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
            tvMainStatus.setTextColor(ContextCompat.getColor(this, R.color.emerald));
        } else {
            tvMainStatus.setText(R.string.status_inactive);
            tvMainStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        }
    }

    private void setupRecyclerView() {
        adapter = new LogAdapter(logList);
        rvLogs.setLayoutManager(new LinearLayoutManager(this));
        rvLogs.setAdapter(adapter);
    }

    private void checkPermissionsAndPrompts() {
        // SMS
        if (PermissionUtils.hasSmsPermissions(this)) {
            btnSmsPermission.setText("Granted ✅");
            btnSmsPermission.setTextColor(ContextCompat.getColor(this, R.color.emerald));
            btnSmsPermission.setEnabled(false);
        } else {
            btnSmsPermission.setText("Grant Permission ⚠️");
            btnSmsPermission.setTextColor(ContextCompat.getColor(this, R.color.amber));
            btnSmsPermission.setEnabled(true);
            requestSmsPermissions();
        }

        // Battery Optimization
        if (PermissionUtils.isBatteryOptimizationIgnored(this)) {
            btnBatteryOptimization.setText("Unrestricted ✅");
            btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.emerald));
            btnBatteryOptimization.setEnabled(false);
        } else {
            btnBatteryOptimization.setText("Fix Setting ⚠️");
            btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.amber));
            btnBatteryOptimization.setEnabled(true);
        }

        // Notifications for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!PermissionUtils.hasNotificationPermission(this)) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQ_NOTIF);
            }
        }
    }

    private void requestSmsPermissions() {
        ActivityCompat.requestPermissions(
            this,
            new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS},
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
                btnPingServer.setText("Test Ping");
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
    protected void onResume() {
        super.onResume();
        checkPermissionsAndPrompts();
        refreshLogsAndStats();

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
    private static class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {
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
            holder.tvSender.setText(item.sender != null ? item.sender : "Unknown");
            holder.tvTime.setText(item.getFormattedTime());
            holder.tvContent.setText(item.body);

            String status = item.status != null ? item.status : "RECEIVED";
            holder.tvStatusBadge.setText(status);

            Context ctx = holder.itemView.getContext();
            if ("MATCHED".equalsIgnoreCase(status)) {
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.emerald));
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_pill_badge);
            } else if ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.rose));
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_pill_badge);
            } else {
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.amber));
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_pill_badge);
            }

            if (item.amount != null && item.amount > 0) {
                holder.tvAmount.setVisibility(View.VISIBLE);
                holder.tvAmount.setText(String.format("$%.2f", item.amount));
            } else {
                holder.tvAmount.setVisibility(View.GONE);
            }

            if (item.matchedOrderId != null && !item.matchedOrderId.isEmpty()) {
                holder.tvOrderId.setVisibility(View.VISIBLE);
                holder.tvOrderId.setText("#" + item.matchedOrderId);
            } else {
                holder.tvOrderId.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView tvSender;
            final TextView tvTime;
            final TextView tvStatusBadge;
            final TextView tvAmount;
            final TextView tvOrderId;
            final TextView tvContent;

            ViewHolder(View v) {
                super(v);
                tvSender = v.findViewById(R.id.tvSender);
                tvTime = v.findViewById(R.id.tvTime);
                tvStatusBadge = v.findViewById(R.id.tvStatusBadge);
                tvAmount = v.findViewById(R.id.tvAmount);
                tvOrderId = v.findViewById(R.id.tvOrderId);
                tvContent = v.findViewById(R.id.tvContent);
            }
        }
    }
}
