package so.oskarshop.sms.update;

public class UpdateInfo {
    private final int versionCode;
    private final String versionName;
    private final String apkUrl;
    private final String fallbackUrl;
    private final String changelog;
    private final boolean mandatory;
    private final long releaseDate;

    public UpdateInfo(int versionCode, String versionName, String apkUrl, String fallbackUrl, String changelog, boolean mandatory, long releaseDate) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.fallbackUrl = fallbackUrl;
        this.changelog = changelog;
        this.mandatory = mandatory;
        this.releaseDate = releaseDate;
    }

    public int getVersionCode() {
        return versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getApkUrl() {
        return apkUrl;
    }

    public String getFallbackUrl() {
        return fallbackUrl;
    }

    public String getChangelog() {
        return changelog;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public long getReleaseDate() {
        return releaseDate;
    }
}
