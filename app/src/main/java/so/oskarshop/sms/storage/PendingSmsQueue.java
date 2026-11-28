package so.oskarshop.sms.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import so.oskarshop.sms.util.SmsInboxTracker;

/**
 * Persistent disk-backed FIFO queue for captured carrier SMS messages.
 * Guarantees zero message loss when the phone is sleeping, offline, or on slow/unstable networks.
 */
public class PendingSmsQueue {
    private static final String PREF_NAME = "oskar_pending_sms_queue";
    private static final String KEY_QUEUE = "pending_items";
    private static final int MAX_QUEUE_SIZE = 200;
    private static final Gson gson = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<PendingSms>>() {}.getType();

    public static class PendingSms {
        public String id;
        public String sender;
        public String body;
        public long capturedAt;
        public int attempts;

        public PendingSms(String id, String sender, String body, long capturedAt) {
            this.id = id;
            this.sender = sender;
            this.body = body;
            this.capturedAt = capturedAt;
            this.attempts = 0;
        }
    }

    public static synchronized boolean enqueue(Context context, String sender, String body, long capturedAt) {
        if (sender == null || body == null || body.trim().isEmpty()) return false;
        String fingerprint = SmsInboxTracker.computeFingerprint(sender, body, capturedAt);
        List<PendingSms> queue = loadQueue(context);

        for (PendingSms item : queue) {
            if (item != null && fingerprint.equals(item.id)) {
                return false; // Already queued
            }
        }

        if (queue.size() >= MAX_QUEUE_SIZE) {
            queue.remove(0);
        }
        queue.add(new PendingSms(fingerprint, sender, body, capturedAt));
        saveQueue(context, queue);
        return true;
    }

    public static synchronized List<PendingSms> peekAll(Context context) {
        return new ArrayList<>(loadQueue(context));
    }

    public static synchronized void removeById(Context context, String id) {
        if (id == null) return;
        List<PendingSms> queue = loadQueue(context);
        Iterator<PendingSms> it = queue.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            PendingSms item = it.next();
            if (item != null && id.equals(item.id)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            saveQueue(context, queue);
        }
    }

    public static synchronized int size(Context context) {
        return loadQueue(context).size();
    }

    private static List<PendingSms> loadQueue(Context context) {
        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_QUEUE, null);
            if (json == null || json.isEmpty()) return new ArrayList<>();
            List<PendingSms> list = gson.fromJson(json, LIST_TYPE);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void saveQueue(Context context, List<PendingSms> queue) {
        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_QUEUE, gson.toJson(queue)).commit();
        } catch (Exception ignored) {}
    }
}
