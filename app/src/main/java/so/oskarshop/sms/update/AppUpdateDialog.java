package so.oskarshop.sms.update;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import so.oskarshop.sms.R;
import so.oskarshop.sms.util.PermissionUtils;

/**
 * Modern Light-themed full-screen non-dismissible force update screen.
 * - 3D Phone & Rocket Illustration with bottom decorative wave
 * - App Header Card with v[current] → v[target] badge
 * - "A new version is available" headline & subtitle
 * - Clean "What's New?" card with dynamic checkmarked items (1, 3, or more lines)
 * - Single "Update Now" button (no "Not Now" button)
 * - Sleek horizontal progress bar during download
 * - Automatically invokes package installer upon download completion (zero manual click required)
 * - Non-dismissible (back key blocked to enforce mandatory staff update)
 */
public class AppUpdateDialog extends DialogFragment {
    public static final String TAG = "AppUpdateDialog";
    public static final int REQ_INSTALL_PERM = 3001;

    private UpdateInfo updateInfo;
    private File downloadedApkFile;
    private boolean isDownloading = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LinearLayout layoutIdleState;
    private LinearLayout layoutDownloadingState;
    private LinearLayout layoutErrorState;
    private LinearLayout llChangelogItems;

    private TextView tvVersionBadge;
    private TextView tvDownloadingLabel;
    private TextView tvDownloadPercent;
    private ProgressBar progressBarUpdate;
    private TextView tvErrorMessage;

    private Button btnUpdate;
    private Button btnRetry;

    public static AppUpdateDialog newInstance(UpdateInfo info) {
        AppUpdateDialog dialog = new AppUpdateDialog();
        dialog.updateInfo = info;
        return dialog;
    }

    public static void showUpdate(FragmentManager fm, UpdateInfo info) {
        if (fm == null || fm.isStateSaved() || fm.findFragmentByTag(TAG) != null) return;
        AppUpdateDialog dialog = newInstance(info);
        dialog.show(fm, TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Full screen non-dismissible dialog
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Light_NoTitleBar_Fullscreen);
        setCancelable(false);
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
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        
        // Prevent dismissal on back key press to enforce update
        dialog.setOnKeyListener((dialogInterface, keyCode, event) -> {
            return keyCode == KeyEvent.KEYCODE_BACK;
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
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
    }

    private void initViews(View view) {
        layoutIdleState = view.findViewById(R.id.layoutIdleState);
        layoutDownloadingState = view.findViewById(R.id.layoutDownloadingState);
        layoutErrorState = view.findViewById(R.id.layoutErrorState);
        llChangelogItems = view.findViewById(R.id.llChangelogItems);

        tvVersionBadge = view.findViewById(R.id.tvVersionBadge);
        tvDownloadingLabel = view.findViewById(R.id.tvDownloadingLabel);
        tvDownloadPercent = view.findViewById(R.id.tvDownloadPercent);
        progressBarUpdate = view.findViewById(R.id.progressBarUpdate);
        tvErrorMessage = view.findViewById(R.id.tvErrorMessage);

        btnUpdate = view.findViewById(R.id.btnUpdate);
        btnRetry = view.findViewById(R.id.btnRetry);

        if (btnUpdate != null) {
            btnUpdate.setOnClickListener(v -> handleUpdateClick());
        }
        if (btnRetry != null) {
            btnRetry.setOnClickListener(v -> handleUpdateClick());
        }
    }

    private void populateData() {
        if (updateInfo == null) return;

        Context ctx = getContext();
        if (ctx == null) return;

        String currentVer = AppUpdateManager.getInstance(ctx).getCurrentVersionName();
        if (tvVersionBadge != null) {
            tvVersionBadge.setText("v" + currentVer + " → v" + updateInfo.getVersionName());
        }

        if (llChangelogItems != null) {
            llChangelogItems.removeAllViews();
            List<String> items = parseChangelogLines(updateInfo.getChangelog());
            LayoutInflater inflater = LayoutInflater.from(ctx);

            for (String itemText : items) {
                View itemView = inflater.inflate(R.layout.item_update_changelog, llChangelogItems, false);
                TextView tvItem = itemView.findViewById(R.id.tvChangelogItem);
                tvItem.setText(itemText);
                llChangelogItems.addView(itemView);
            }
        }
    }

    /**
     * Parses clean, short feature update lines. Supports 1, 3, or more lines dynamically.
     */
    private List<String> parseChangelogLines(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            result.add("Improved SMS detection & verification");
            result.add("Faster and more stable performance");
            result.add("Fixed known issues and crashes");
            result.add("General improvements and optimizations");
            return result;
        }

        String[] lines = raw.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // Remove any bullet prefixes (•, -, *, ✓)
            while (trimmed.startsWith("•") || trimmed.startsWith("-") || trimmed.startsWith("*") || trimmed.startsWith("✓")) {
                trimmed = trimmed.substring(1).trim();
            }
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }

        if (result.isEmpty()) {
            result.add("General improvements and optimizations");
        }
        return result;
    }

    private void handleUpdateClick() {
        Context ctx = getContext();
        if (ctx == null || getActivity() == null || updateInfo == null) return;

        // If file already exists and valid, trigger package installer immediately
        if (downloadedApkFile != null && downloadedApkFile.exists() && downloadedApkFile.length() > 100 * 1024) {
            triggerInstallation(downloadedApkFile);
            return;
        }

        // Check Unknown Sources Permission: trigger request on click
        if (!PermissionUtils.canRequestPackageInstalls(ctx)) {
            Toast.makeText(ctx, "Please allow 'Install unknown apps' to install the update", Toast.LENGTH_SHORT).show();
            PermissionUtils.requestInstallPermission(getActivity(), REQ_INSTALL_PERM);
            return;
        }

        startDownload();
    }

    /**
     * Called when returning from System Settings after granting install permission
     */
    public void onPermissionResult() {
        Context ctx = getContext();
        if (ctx == null) return;

        if (PermissionUtils.canRequestPackageInstalls(ctx)) {
            if (downloadedApkFile != null && downloadedApkFile.exists()) {
                triggerInstallation(downloadedApkFile);
            } else if (!isDownloading) {
                startDownload();
            }
        }
    }

    private void startDownload() {
        Context ctx = getContext();
        if (ctx == null || updateInfo == null) return;

        isDownloading = true;

        // Transition UI to download state
        if (layoutIdleState != null) layoutIdleState.setVisibility(View.GONE);
        if (layoutErrorState != null) layoutErrorState.setVisibility(View.GONE);
        if (layoutDownloadingState != null) layoutDownloadingState.setVisibility(View.VISIBLE);

        if (tvDownloadingLabel != null) tvDownloadingLabel.setText("Downloading update...");
        if (tvDownloadPercent != null) tvDownloadPercent.setText("0%");
        if (progressBarUpdate != null) progressBarUpdate.setProgress(0);

        AppUpdateManager.getInstance(ctx).downloadUpdate(updateInfo, new AppUpdateManager.DownloadCallback() {
            @Override
            public void onProgress(int percentage, long bytesDownloaded, long totalBytes) {
                if (!isAdded()) return;

                if (percentage >= 0) {
                    if (progressBarUpdate != null) progressBarUpdate.setProgress(percentage);
                    if (tvDownloadPercent != null) tvDownloadPercent.setText(percentage + "%");
                }
            }

            @Override
            public void onCompleted(File apkFile) {
                if (!isAdded()) return;
                isDownloading = false;
                downloadedApkFile = apkFile;

                if (progressBarUpdate != null) progressBarUpdate.setProgress(100);
                if (tvDownloadPercent != null) tvDownloadPercent.setText("100%");
                if (tvDownloadingLabel != null) tvDownloadingLabel.setText("Installing update...");

                // Automatically and seamlessly launch the package installer
                mainHandler.postDelayed(() -> {
                    if (isAdded()) {
                        triggerInstallation(apkFile);
                    }
                }, 200);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                isDownloading = false;

                if (layoutDownloadingState != null) layoutDownloadingState.setVisibility(View.GONE);
                if (layoutErrorState != null) layoutErrorState.setVisibility(View.VISIBLE);
                if (tvErrorMessage != null) tvErrorMessage.setText("Download failed: " + message);
            }
        });
    }

    private void triggerInstallation(File apkFile) {
        if (getActivity() == null || getContext() == null) return;
        AppUpdateManager.getInstance(requireContext()).installApk(getActivity(), apkFile, REQ_INSTALL_PERM);
    }
}
