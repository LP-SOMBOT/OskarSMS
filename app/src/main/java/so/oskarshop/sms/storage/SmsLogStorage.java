package so.oskarshop.sms.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import so.oskarshop.sms.model.SmsLogEntry;

public class SmsLogStorage {
    private static final String PREF_NAME = "oskar_sms_logs";
    private static final String KEY_LOGS = "logs_json";
    private static final int MAX_LOGS = 50;

    private final SharedPreferences prefs;
    private final Gson gson;

    public SmsLogStorage(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
    }

    public synchronized void addLog(SmsLogEntry entry) {
        List<SmsLogEntry> list = getLogs();
        list.add(0, entry);
        if (list.size() > MAX_LOGS) {
            list = list.subList(0, MAX_LOGS);
        }
        String json = gson.toJson(list);
        prefs.edit().putString(KEY_LOGS, json).apply();
    }

    public synchronized List<SmsLogEntry> getLogs() {
        String json = prefs.getString(KEY_LOGS, null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        Type type = new TypeToken<ArrayList<SmsLogEntry>>() {}.getType();
        try {
            List<SmsLogEntry> list = gson.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public synchronized void clearLogs() {
        prefs.edit().remove(KEY_LOGS).apply();
    }
}
