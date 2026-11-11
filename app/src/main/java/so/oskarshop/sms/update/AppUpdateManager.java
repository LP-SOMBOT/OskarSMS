package so.oskarshop.sms.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.content.FileProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import so.oskarshop.sms.util.PermissionUtils;

public class AppUpdateManager {
    private static final String TAG = "AppUpdateManager";

    public static final String PRIMARY_UPDATE_URL = "https://oskarshop.so/api/apk/update";
    public static final String FALLBACK_GITHUB_API = "https://api.github.com/repos/LP-SOMBOT/OskarSMS/releases/latest";

    private static AppUpdateManager instance;

    private final Context context;
    private final OkHttpClient httpClient;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private boolean isDownloading = false;

    public interface CheckCallback {
        void onUpdateAvailable(UpdateInfo info);
        void onNoUpdate();
        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(int percentage, long bytesDownloaded, long totalBytes);
        void onCompleted(File apkFile);
        void onError(String message);
    }

    public static synchronized AppUpdateManager getInstance(Context context) {
        if (instance == null) {
            instance = new AppUpdateManager(context.getApplicationContext());
        }
        return instance;
    }

    private AppUpdateManager(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public long getCurrentVersionCode() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (Exception e) {
            return 1;
        }
    }

    public String getCurrentVersionName() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "1.0.0";
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    /**
     * Check for updates in background (OskarShop API -> GitHub Releases fallback)
     */
    public void checkForUpdate(CheckCallback callback) {
        executor.execute(() -> {
            UpdateInfo info = fetchUpdateFromOskarShop();
            if (info == null) {
                info = fetchUpdateFromGitHub();
            }

            if (info != null) {
                long current = getCurrentVersionCode();
                if (info.getVersionCode() > current) {
                    UpdateInfo finalInfo = info;
                    mainHandler.post(() -> callback.onUpdateAvailable(finalInfo));
                    return;
                } else {
                    mainHandler.post(callback::onNoUpdate);
                    return;
                }
            }

            mainHandler.post(() -> callback.onError("Could not check for updates"));
        });
    }

    private UpdateInfo fetchUpdateFromOskarShop() {
        try {
            Request request = new Request.Builder()
                .url(PRIMARY_UPDATE_URL)
                .addHeader("User-Agent", "OskarSMS-Android/" + getCurrentVersionName())
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                String res = response.body().string();
                JsonObject obj = JsonParser.parseString(res).getAsJsonObject();

                int code = obj.has("versionCode") ? obj.get("versionCode").getAsInt() : 0;
                String name = obj.has("versionName") ? obj.get("versionName").getAsString() : "1.0.0";
                String url = obj.has("apkUrl") ? obj.get("apkUrl").getAsString() : "";
                String fallback = obj.has("fallbackUrl") ? obj.get("fallbackUrl").getAsString() : "";
                String notes = obj.has("changelog") ? obj.get("changelog").getAsString() : "New features and stability improvements.";
                boolean mandatory = obj.has("mandatory") && obj.get("mandatory").getAsBoolean();
                long date = obj.has("releaseDate") ? obj.get("releaseDate").getAsLong() : System.currentTimeMillis();

                if (code > 0 && !url.isEmpty()) {
                    return new UpdateInfo(code, name, url, fallback, notes, mandatory, date);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "OskarShop update endpoint failed: " + e.getMessage());
        }
        return null;
    }

    private UpdateInfo fetchUpdateFromGitHub() {
        try {
            Request request = new Request.Builder()
                .url(FALLBACK_GITHUB_API)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .addHeader("User-Agent", "OskarSMS-Android/" + getCurrentVersionName())
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                String res = response.body().string();
                JsonObject obj = JsonParser.parseString(res).getAsJsonObject();

                String tagName = obj.has("tag_name") ? obj.get("tag_name").getAsString() : "";
                String body = obj.has("body") ? obj.get("body").getAsString() : "Latest release from GitHub";

                String apkDownloadUrl = "";
                if (obj.has("assets") && obj.get("assets").isJsonArray()) {
                    JsonArray assets = obj.getAsJsonArray("assets");
                    for (JsonElement el : assets) {
                        if (!el.isJsonObject()) continue;
                        JsonObject asset = el.getAsJsonObject();
                        String name = asset.has("name") ? asset.get("name").getAsString() : "";
                        if (name.endsWith(".apk")) {
                            apkDownloadUrl = asset.has("browser_download_url") ? asset.get("browser_download_url").getAsString() : "";
                            break;
                        }
                    }
                }

                if (apkDownloadUrl.isEmpty() && !tagName.isEmpty()) {
                    apkDownloadUrl = "https://github.com/LP-SOMBOT/OskarSMS/releases/download/" + tagName + "/OskarSMS.apk";
                }

                // Extract numeric version code from tag (e.g. v1.1.4 -> 7 or 114)
                String cleanName = tagName.replace("v", "").trim();
                int inferredCode = parseVersionNameToCode(cleanName);

                if (!apkDownloadUrl.isEmpty()) {
                    return new UpdateInfo(inferredCode, cleanName, apkDownloadUrl, apkDownloadUrl, body, false, System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "GitHub release check failed: " + e.getMessage());
        }
        return null;
    }

    private int parseVersionNameToCode(String versionName) {
        try {
            String[] parts = versionName.split("\\.");
            int code = 0;
            for (String p : parts) {
                code = code * 10 + Integer.parseInt(p.replaceAll("\\D", ""));
            }
            return Math.max(code, 7);
        } catch (Exception e) {
            return 7;
        }
    }

    /**
     * Download the APK with real-time percentage progress
     */
    public void downloadUpdate(UpdateInfo info, DownloadCallback callback) {
        if (isDownloading) {
            callback.onError("Download already in progress");
            return;
        }

        isDownloading = true;

        executor.execute(() -> {
            File targetDir = new File(context.getExternalFilesDir(null), "apk_updates");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }

            File apkFile = new File(targetDir, "OskarSMS-v" + info.getVersionName() + ".apk");
            if (apkFile.exists()) {
                apkFile.delete();
            }

            String downloadUrl = info.getApkUrl();
            boolean success = downloadFile(downloadUrl, apkFile, callback);

            if (!success && info.getFallbackUrl() != null && !info.getFallbackUrl().equalsIgnoreCase(downloadUrl)) {
                Log.i(TAG, "Primary download failed, attempting fallback URL: " + info.getFallbackUrl());
                success = downloadFile(info.getFallbackUrl(), apkFile, callback);
            }

            isDownloading = false;

            if (success && apkFile.exists() && apkFile.length() > 100 * 1024) {
                mainHandler.post(() -> callback.onCompleted(apkFile));
            } else {
                if (apkFile.exists()) apkFile.delete();
                mainHandler.post(() -> callback.onError("Failed to download APK file. Please check connection."));
            }
        });
    }

    private boolean downloadFile(String url, File targetFile, DownloadCallback callback) {
        try {
            Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "OskarSMS-Android/" + getCurrentVersionName())
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return false;
                ResponseBody body = response.body();
                if (body == null) return false;

                long totalBytes = body.contentLength();
                long bytesDownloaded = 0;

                try (InputStream is = body.byteStream();
                     FileOutputStream fos = new FileOutputStream(targetFile)) {

                    byte[] buffer = new byte[8192];
                    int read;
                    long lastProgressUpdate = 0;

                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                        bytesDownloaded += read;

                        long now = System.currentTimeMillis();
                        if (now - lastProgressUpdate > 100 || bytesDownloaded == totalBytes) {
                            lastProgressUpdate = now;
                            int percent = totalBytes > 0 ? (int) ((bytesDownloaded * 100) / totalBytes) : -1;
                            long finalBytesDownloaded = bytesDownloaded;
                            mainHandler.post(() -> callback.onProgress(percent, finalBytesDownloaded, totalBytes));
                        }
                    }
                    fos.flush();
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error downloading APK from " + url, e);
            return false;
        }
    }

    /**
     * Start APK installation via Android Package Installer
     */
    public void installApk(Activity activity, File apkFile, int permissionReqCode) {
        if (apkFile == null || !apkFile.exists()) {
            mainHandler.post(() -> android.widget.Toast.makeText(activity, "APK file not found", android.widget.Toast.LENGTH_SHORT).show());
            return;
        }

        // 1. Check Install Unknown Apps Permission
        if (!PermissionUtils.canRequestPackageInstalls(activity)) {
            PermissionUtils.requestInstallPermission(activity, permissionReqCode);
            return;
        }

        // 2. Launch Android Package Installer using FileProvider
        try {
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile
                );
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // Grant read URI permission to resolving package installer activities
            List<ResolveInfo> resInfoList = activity.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                activity.grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching package installer", e);
            android.widget.Toast.makeText(activity, "Installation failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
