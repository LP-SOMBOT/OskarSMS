package so.oskarshop.sms.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Universal SMS Inbox Tracker and Deduplicator.
 * Captures EVERY single SMS from ANY sender and ANY number without restriction.
 */
public class SmsInboxTracker {
    private static final String TAG = "OskarSmsTracker";
    private static final String PREF_NAME = "oskar_sms_tracker_cache";
    private static final String KEY_PROCESSED_HASHES = "processed_hashes";
    private static final int MAX_CACHE_SIZE = 1000;

    public static class RawSms {
        public final long id;
        public final String address;
        public final String body;
        public final long date;

        public RawSms(long id, String address, String body, long date) {
            this.id = id;
            this.address = address;
            this.body = body;
            this.date = date;
        }
    }

    /**
     * Captures 100% of all incoming SMS without filtering by sender, number, or keywords.
     * All filtering and parsing is handled centrally by the server pipeline.
     */
    public static boolean isCandidateSms(String sender, String body) {
        return body != null && !body.trim().isEmpty();
    }

    /**
     * Computes a resilient fingerprint for deduplication between BroadcastReceiver and ContentObserver.
     */
    public static String computeFingerprint(String sender, String body, long timestamp) {
        String cleanSender = sender != null ? sender.replaceAll("[^0-9a-zA-Z]", "") : "";
        String cleanBody = body != null ? body.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT) : "";
        // 45-second rounding window to deduplicate identical broadcasts and inbox entries
        long roundedTime = (timestamp / 45000) * 45000;
        return cleanSender + ":" + roundedTime + ":" + cleanBody.hashCode();
    }

    /**
     * Atomically checks if a fingerprint is new. If new, stores it and returns true.
     */
    public static synchronized boolean markIfNew(Context context, String fingerprint) {
        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            Set<String> set = new HashSet<>(prefs.getStringSet(KEY_PROCESSED_HASHES, new HashSet<>()));

            if (set.contains(fingerprint)) {
                return false;
            }

            if (set.size() >= MAX_CACHE_SIZE) {
                set.clear();
            }

            set.add(fingerprint);
            prefs.edit().putStringSet(KEY_PROCESSED_HASHES, set).apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in markIfNew: " + e.getMessage());
            return true;
        }
    }

    /**
     * Queries the device's incoming SMS inbox provider (content://sms/inbox).
     * Retrieves ALL messages regardless of sender address.
     */
    public static List<RawSms> queryRecentInbox(Context context, long sinceMs) {
        List<RawSms> results = new ArrayList<>();
        Uri uri = Uri.parse("content://sms/inbox");

        try (Cursor cursor = context.getContentResolver().query(
            uri,
            new String[]{"_id", "address", "body", "date"},
            "date > ?",
            new String[]{String.valueOf(sinceMs)},
            "date DESC"
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int idIdx = cursor.getColumnIndex("_id");
                int addrIdx = cursor.getColumnIndex("address");
                int bodyIdx = cursor.getColumnIndex("body");
                int dateIdx = cursor.getColumnIndex("date");

                do {
                    long id = idIdx != -1 ? cursor.getLong(idIdx) : 0;
                    String address = addrIdx != -1 ? cursor.getString(addrIdx) : "Unknown";
                    String body = bodyIdx != -1 ? cursor.getString(bodyIdx) : "";
                    long date = dateIdx != -1 ? cursor.getLong(dateIdx) : System.currentTimeMillis();

                    if (body != null && !body.trim().isEmpty()) {
                        results.add(new RawSms(id, address, body, date));
                    }
                } while (cursor.moveToNext() && results.size() < 40);
            }
        } catch (SecurityException se) {
            Log.e(TAG, "Missing READ_SMS permission to query inbox: " + se.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error querying SMS inbox: " + e.getMessage());
        }

        return results;
    }
}
