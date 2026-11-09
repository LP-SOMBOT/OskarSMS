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
    private TextView tvTotalForwarded;
    private TextView tvTotalMatched;
    private Button btnSmsPermission;
    private Button btnBatteryOptimization;
    private Button btnPingServer;
    private TextView tvEndpointUrl;
    private TextView tvEmptyState;
    private RecyclerView rvLogs;
    private LogAdapter adapter;
    private final List<SmsLogEntry> logList = new ArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver logUpdateReceiver = new BroadcastReceiver() {
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

        // Start background service if enabled
        if (settings.isEnabled()) {
            SmsForwarderService.startListening(this);
        }
    }

    private void initViews() {
        tvMainStatus = findViewById(R.id.tvMainStatus);
        switchEnable = findViewById(R.id.switchEnable);
        tvTotalForwarded = findViewById(R.id.tvTotalForwarded);
        tvTotalMatched = findViewById(R.id.tvTotalMatched);
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
                Toast.makeText(this, "OskarSMS Activated: Monitoring EVC Plus", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "OskarSMS Paused", Toast.LENGTH_SHORT).show();
            }
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
                    Toast.makeText(this, "✅ Connected to OskarShop server!", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "⚠️ Could not reach server. Check internet connection.", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void refreshLogsAndStats() {
        tvTotalForwarded.setText(String.valueOf(settings.getTotalForwarded()));
        tvTotalMatched.setText(String.valueOf(settings.getTotalMatched()));

        List<SmsLogEntry> logs = logStorage.getLogs();
        logList.clear();
        logList.addAll(logs);
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
        ContextCompat.registerReceiver(
            this,
            logUpdateReceiver,
            new IntentFilter(SmsForwarderService.ACTION_NEW_LOG),
            ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(logUpdateReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        checkPermissionsAndPrompts();
    }

    // RecyclerView Adapter for Log Entries
    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {
        private final List<SmsLogEntry> items;

        LogAdapter(List<SmsLogEntry> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sms_log, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SmsLogEntry item = items.get(position);

            holder.tvLogTime.setText(item.getFormattedTime());
            holder.tvRawMessage.setText(item.getRawMessage());

            // Amount
            if (item.getAmount() != null) {
                holder.tvAmount.setText(String.format("$%.2f", item.getAmount()));
                holder.tvAmount.setVisibility(View.VISIBLE);
            } else {
                holder.tvAmount.setVisibility(View.GONE);
            }

            // Phone
            if (item.getPayerPhone() != null && !item.getPayerPhone().isEmpty()) {
                holder.tvPhone.setText("From: " + item.getPayerPhone());
                holder.tvPhone.setVisibility(View.VISIBLE);
            } else {
                holder.tvPhone.setText("Sender: " + item.getSender());
            }

            // Status Badge & Order ID
            String status = item.getStatus() != null ? item.getStatus() : "RECEIVED";
            holder.tvStatusBadge.setText(status);

            if ("MATCHED".equalsIgnoreCase(status)) {
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.emerald));
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_active);
                if (item.getMatchedOrderId() != null) {
                    holder.tvMatchedOrder.setText("Order #" + item.getMatchedOrderId());
                    holder.tvMatchedOrder.setVisibility(View.VISIBLE);
                } else {
                    holder.tvMatchedOrder.setVisibility(View.GONE);
                }
            } else if ("FAILED".equalsIgnoreCase(status)) {
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
            return items.size();
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
