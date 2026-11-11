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
import so.oskarshop.sms.R;
import so.oskarshop.sms.util.PermissionUtils;

/**
 * 1xBet-styled full-screen non-dismissible force update screen.
 * - Full-bleed background image
 * - English text: "A new version of the app is available, press \"Update\" to install"
 * - "What's new?" and "Update" buttons
 * - Sleek horizontal progress bar during download
 * - Automatically invokes package installer upon download completion (zero manual click required)
 * - Non-dismissible (no X button, back button blocked)
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
    private LinearLayout layoutChangelogCard;

    private TextView tvVersionTitle;
    private TextView tvChangelogContent;
    private TextView tvDownloadingLabel;
    private TextView tvDownloadPercent;
    private ProgressBar progressBarUpdate;
    private TextView tvErrorMessage;

    private Button btnWhatsNew;
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
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
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
    }

    private void initViews(View view) {
        layoutIdleState = view.findViewById(R.id.layoutIdleState);
        layoutDownloadingState = view.findViewById(R.id.layoutDownloadingState);
        layoutErrorState = view.findViewById(R.id.layoutErrorState);
        layoutChangelogCard = view.findViewById(R.id.layoutChangelogCard);

        tvVersionTitle = view.findViewById(R.id.tvVersionTitle);
        tvChangelogContent = view.findViewById(R.id.tvChangelogContent);
        tvDownloadingLabel = view.findViewById(R.id.tvDownloadingLabel);
        tvDownloadPercent = view.findViewById(R.id.tvDownloadPercent);
        progressBarUpdate = view.findViewById(R.id.progressBarUpdate);
        tvErrorMessage = view.findViewById(R.id.tvErrorMessage);

        btnWhatsNew = view.findViewById(R.id.btnWhatsNew);
        btnUpdate = view.findViewById(R.id.btnUpdate);
        btnRetry = view.findViewById(R.id.btnRetry);
        TextView btnCloseChangelog = view.findViewById(R.id.btnCloseChangelog);

        btnWhatsNew.setOnClickListener(v -> {
            if (layoutChangelogCard.getVisibility() == View.VISIBLE) {
                layoutChangelogCard.setVisibility(View.GONE);
            } else {
                layoutChangelogCard.setVisibility(View.VISIBLE);
            }
        });

        if (btnCloseChangelog != null) {
            btnCloseChangelog.setOnClickListener(v -> layoutChangelogCard.setVisibility(View.GONE));
        }

        btnUpdate.setOnClickListener(v -> handleUpdateClick());
        btnRetry.setOnClickListener(v -> handleUpdateClick());
    }

    private void populateData() {
        if (updateInfo == null) return;

        tvVersionTitle.setText("What's new in v" + updateInfo.getVersionName() + ":");
        if (updateInfo.getChangelog() != null && !updateInfo.getChangelog().trim().isEmpty()) {
            tvChangelogContent.setText(updateInfo.getChangelog());
        }
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

        // Transition UI to 1xBet download state
        layoutIdleState.setVisibility(View.GONE);
        layoutChangelogCard.setVisibility(View.GONE);
        layoutErrorState.setVisibility(View.GONE);
        layoutDownloadingState.setVisibility(View.VISIBLE);

        tvDownloadingLabel.setText("Downloading update...");
        tvDownloadPercent.setText("0%");
        progressBarUpdate.setProgress(0);

        AppUpdateManager.getInstance(ctx).downloadUpdate(updateInfo, new AppUpdateManager.DownloadCallback() {
            @Override
            public void onProgress(int percentage, long bytesDownloaded, long totalBytes) {
                if (!isAdded()) return;

                if (percentage >= 0) {
                    progressBarUpdate.setProgress(percentage);
                    tvDownloadPercent.setText(percentage + "%");
                }
            }

            @Override
            public void onCompleted(File apkFile) {
                if (!isAdded()) return;
                isDownloading = false;
                downloadedApkFile = apkFile;

                progressBarUpdate.setProgress(100);
                tvDownloadPercent.setText("100%");
                tvDownloadingLabel.setText("Installing update...");

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

                layoutDownloadingState.setVisibility(View.GONE);
                layoutErrorState.setVisibility(View.VISIBLE);
                tvErrorMessage.setText("Download failed: " + message);
            }
        });
    }

    private void triggerInstallation(File apkFile) {
        if (getActivity() == null || getContext() == null) return;
        AppUpdateManager.getInstance(requireContext()).installApk(getActivity(), apkFile, REQ_INSTALL_PERM);
    }
}
