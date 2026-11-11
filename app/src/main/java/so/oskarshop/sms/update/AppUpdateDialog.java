package so.oskarshop.sms.update;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import java.io.File;
import java.util.Locale;
import so.oskarshop.sms.R;
import so.oskarshop.sms.util.PermissionUtils;

public class AppUpdateDialog extends DialogFragment {
    public static final String TAG = "AppUpdateDialog";
    public static final int REQ_INSTALL_PERM = 3001;

    private UpdateInfo updateInfo;
    private File downloadedApkFile;
    private boolean isDownloading = false;

    private TextView tvCurrentVersion;
    private TextView tvNewVersion;
    private TextView tvChangelog;
    private LinearLayout layoutPermissionNotice;
    private TextView tvPermissionIcon;
    private TextView tvPermissionStatus;
    private LinearLayout layoutDownloadProgress;
    private TextView tvProgressStatus;
    private TextView tvProgressPercentage;
    private ProgressBar progressBarUpdate;
    private TextView tvProgressBytes;
    private Button btnActionUpdate;
    private TextView btnDismissUpdate;
    private ImageButton btnCloseUpdateDialog;

    public static AppUpdateDialog newInstance(UpdateInfo info) {
        AppUpdateDialog dialog = new AppUpdateDialog();
        dialog.updateInfo = info;
        return dialog;
    }

    public static void showUpdate(FragmentManager fm, UpdateInfo info) {
        if (fm.findFragmentByTag(TAG) != null) return;
        AppUpdateDialog dialog = newInstance(info);
        dialog.show(fm, TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Make it full screen dismissible dialog
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_app_update, container, false);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setCancelable(true);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initViews(view);
        populateData();
        updatePermissionNotice();
    }

    private void initViews(View view) {
        tvCurrentVersion = view.findViewById(R.id.tvCurrentVersion);
        tvNewVersion = view.findViewById(R.id.tvNewVersion);
        tvChangelog = view.findViewById(R.id.tvChangelog);
        layoutPermissionNotice = view.findViewById(R.id.layoutPermissionNotice);
        tvPermissionIcon = view.findViewById(R.id.tvPermissionIcon);
        tvPermissionStatus = view.findViewById(R.id.tvPermissionStatus);
        layoutDownloadProgress = view.findViewById(R.id.layoutDownloadProgress);
        tvProgressStatus = view.findViewById(R.id.tvProgressStatus);
        tvProgressPercentage = view.findViewById(R.id.tvProgressPercentage);
        progressBarUpdate = view.findViewById(R.id.progressBarUpdate);
        tvProgressBytes = view.findViewById(R.id.tvProgressBytes);
        btnActionUpdate = view.findViewById(R.id.btnActionUpdate);
        btnDismissUpdate = view.findViewById(R.id.btnDismissUpdate);
        btnCloseUpdateDialog = view.findViewById(R.id.btnCloseUpdateDialog);

        btnCloseUpdateDialog.setOnClickListener(v -> dismiss());
        btnDismissUpdate.setOnClickListener(v -> dismiss());

        btnActionUpdate.setOnClickListener(v -> handleActionButtonClick());
    }

    private void populateData() {
        Context ctx = getContext();
        if (ctx == null || updateInfo == null) return;

        AppUpdateManager mgr = AppUpdateManager.getInstance(ctx);
        tvCurrentVersion.setText("v" + mgr.getCurrentVersionName());
        tvNewVersion.setText("v" + updateInfo.getVersionName());

        if (updateInfo.getChangelog() != null && !updateInfo.getChangelog().trim().isEmpty()) {
            tvChangelog.setText(updateInfo.getChangelog());
        }
    }

    public void updatePermissionNotice() {
        Context ctx = getContext();
        if (ctx == null) return;

        boolean canInstall = PermissionUtils.canRequestPackageInstalls(ctx);
        if (canInstall) {
            layoutPermissionNotice.setBackgroundResource(R.drawable.bg_status_active);
            tvPermissionIcon.setText("✅");
            tvPermissionStatus.setText("Install Unknown Sources: Permission Granted");
            tvPermissionStatus.setTextColor(ContextCompat.getColor(ctx, R.color.emerald));
        } else {
            layoutPermissionNotice.setBackgroundResource(R.drawable.bg_status_inactive);
            tvPermissionIcon.setText("⚙️");
            tvPermissionStatus.setText("Install Sources: Tap Update to Grant");
            tvPermissionStatus.setTextColor(ContextCompat.getColor(ctx, R.color.amber));
        }
    }

    private void handleActionButtonClick() {
        Context ctx = getContext();
        if (ctx == null || getActivity() == null || updateInfo == null) return;

        // Case 1: APK already downloaded -> launch install immediately
        if (downloadedApkFile != null && downloadedApkFile.exists() && downloadedApkFile.length() > 100 * 1024) {
            triggerInstallation(downloadedApkFile);
            return;
        }

        // Case 2: Check install sources permission first
        if (!PermissionUtils.canRequestPackageInstalls(ctx)) {
            Toast.makeText(ctx, "Fadlan oggolow 'Install unknown apps' si update-ka loo rakibo", Toast.LENGTH_LONG).show();
            PermissionUtils.requestInstallPermission(getActivity(), REQ_INSTALL_PERM);
            return;
        }

        // Case 3: Start Download
        startDownload();
    }

    public void onPermissionGrantedResume() {
        updatePermissionNotice();
        if (downloadedApkFile != null && downloadedApkFile.exists()) {
            triggerInstallation(downloadedApkFile);
        } else if (!isDownloading) {
            startDownload();
        }
    }

    private void startDownload() {
        Context ctx = getContext();
        if (ctx == null || updateInfo == null) return;

        isDownloading = true;
        layoutDownloadProgress.setVisibility(View.VISIBLE);
        btnActionUpdate.setEnabled(false);
        btnActionUpdate.setText("SOO DEJINAYA UPDATE-KA... ⏳");
        btnDismissUpdate.setVisibility(View.GONE);

        AppUpdateManager.getInstance(ctx).downloadUpdate(updateInfo, new AppUpdateManager.DownloadCallback() {
            @Override
            public void onProgress(int percentage, long bytesDownloaded, long totalBytes) {
                if (!isAdded()) return;

                if (percentage >= 0) {
                    progressBarUpdate.setProgress(percentage);
                    tvProgressPercentage.setText(percentage + "%");
                }

                String mbDownloaded = String.format(Locale.US, "%.1f MB", bytesDownloaded / (1024.0 * 1024.0));
                String mbTotal = totalBytes > 0 
                    ? String.format(Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0))
                    : "...";

                tvProgressBytes.setText(mbDownloaded + " / " + mbTotal);
                tvProgressStatus.setText("Soo dejinaya update-ka...");
            }

            @Override
            public void onCompleted(File apkFile) {
                if (!isAdded()) return;
                isDownloading = false;
                downloadedApkFile = apkFile;

                progressBarUpdate.setProgress(100);
                tvProgressPercentage.setText("100%");
                tvProgressStatus.setText("Soo dejintu way dhammaatay! ✅");
                btnActionUpdate.setEnabled(true);
                btnActionUpdate.setText("📦 BILOW XIRITAANKA (INSTALL NOW)");
                btnActionUpdate.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.emerald));

                // Automatically trigger installation progress
                triggerInstallation(apkFile);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                isDownloading = false;
                layoutDownloadProgress.setVisibility(View.GONE);
                btnActionUpdate.setEnabled(true);
                btnActionUpdate.setText("🚀 CUSBOONEYSII HADDA (RETRY)");
                btnDismissUpdate.setVisibility(View.VISIBLE);
                Toast.makeText(requireContext(), "Khalad: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void triggerInstallation(File apkFile) {
        if (getActivity() == null) return;
        AppUpdateManager.getInstance(requireContext()).installApk(getActivity(), apkFile, REQ_INSTALL_PERM);
    }
}
