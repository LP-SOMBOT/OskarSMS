package so.oskarshop.sms.storage;

import android.content.Context;
import android.content.SharedPreferences;

public class SmsSettings {
    private static final String PREF_NAME = "oskar_sms_config";

    public static final String DEFAULT_SERVER_URL = "https://beta.oskarshop.so/api/sms-webhook";
    public static final String DEFAULT_FALLBACK_URL = "https://oskarshop.so/api/sms-webhook";
    public static final String DEFAULT_SECRET_TOKEN = "oskar_sms_sec_9941a87e2f5b40c6";

    private static final String KEY_ENABLED = "is_enabled";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_FALLBACK_URL = "fallback_url";
    private static final String KEY_SECRET_TOKEN = "secret_token";
    private static final String KEY_EVC_ONLY = "evc_only";
    private static final String KEY_TOTAL_FORWARDED = "total_forwarded";
    private static final String KEY_TOTAL_MATCHED = "total_matched";
    private static final String KEY_LAST_PING_SUCCESS = "last_ping_success";
    private static final String KEY_LAST_PING_TIME = "last_ping_time";

    private final SharedPreferences prefs;

    public SmsSettings(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    public String getFallbackUrl() {
        return prefs.getString(KEY_FALLBACK_URL, DEFAULT_FALLBACK_URL);
    }

    public String getSecretToken() {
        return prefs.getString(KEY_SECRET_TOKEN, DEFAULT_SECRET_TOKEN);
    }

    public boolean isEvcOnly() {
        return prefs.getBoolean(KEY_EVC_ONLY, true);
    }

    public void setEvcOnly(boolean evcOnly) {
        prefs.edit().putBoolean(KEY_EVC_ONLY, evcOnly).apply();
    }

    public int getTotalForwarded() {
        return prefs.getInt(KEY_TOTAL_FORWARDED, 0);
    }

    public void incrementTotalForwarded() {
        prefs.edit().putInt(KEY_TOTAL_FORWARDED, getTotalForwarded() + 1).apply();
    }

    public int getTotalMatched() {
        return prefs.getInt(KEY_TOTAL_MATCHED, 0);
    }

    public void incrementTotalMatched() {
        prefs.edit().putInt(KEY_TOTAL_MATCHED, getTotalMatched() + 1).apply();
    }

    public boolean isLastPingSuccess() {
        return prefs.getBoolean(KEY_LAST_PING_SUCCESS, false);
    }

    public long getLastPingTime() {
        return prefs.getLong(KEY_LAST_PING_TIME, 0);
    }

    public void setLastPingResult(boolean success) {
        prefs.edit()
            .putBoolean(KEY_LAST_PING_SUCCESS, success)
            .putLong(KEY_LAST_PING_TIME, System.currentTimeMillis())
            .apply();
    }
}
