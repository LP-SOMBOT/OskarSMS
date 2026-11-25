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
     * Strictly verifies whether an incoming SMS is genuinely from an authentic telecom carrier
     * (EVC-Plus/Hormuud, ZAAD/Telesom, Sahal/Golis, e-Dahab/Somtel) and NOT an injected SMS
     * from a personal subscriber mobile number.
     */
    public static boolean isLegitimateCarrierSender(String sender, String body) {
        if (sender == null || sender.trim().isEmpty()) return false;
        if (body == null || body.trim().isEmpty()) return false;

        String cleanSender = sender.trim().toUpperCase(Locale.ROOT);
        String digitsOnly = cleanSender.replaceAll("[^0-9]", "");

        // 1. HARD BLOCK: Ordinary Personal Phone Numbers (SMS Injection Attack Vector)
        // Somali phone numbers have 8-12 digits (e.g., 25261xxxxxxx, 061xxxxxxx, +25263xxxxxxx)
        if (digitsOnly.length() >= 8) {
            Log.w("OskarSmsSecurity", "SMS Injection blocked: sender [" + sender + "] is a personal phone number, not a carrier receipt.");
            return false;
        }

        // 2. Allow Official Telecom Names & Shortcodes
        boolean isOfficialTelecomName =
            cleanSender.equals("192") ||
            cleanSender.contains("EVC") ||
            cleanSender.contains("HORMUUD") ||
            cleanSender.contains("WAAFI") ||
            cleanSender.equals("100") ||
            cleanSender.equals("123") ||
            cleanSender.contains("ZAAD") ||
            cleanSender.contains("TELESOM") ||
            cleanSender.contains("SAHAL") ||
            cleanSender.contains("GOLIS") ||
            cleanSender.contains("EDAHAB") ||
            cleanSender.contains("E-DAHAB") ||
            cleanSender.contains("SOMTEL") ||
            cleanSender.contains("SOMNET") ||
            cleanSender.contains("JEEB") ||
            cleanSender.contains("KAASH") ||
            cleanSender.contains("PREMIER") ||
            cleanSender.contains("AMAL") ||
            cleanSender.contains("SALAAM") ||
            cleanSender.equals("700") ||
            cleanSender.equals("712") ||
            cleanSender.equals("777") ||
            cleanSender.equals("812") ||
            cleanSender.equals("880");

        // 3. Shortcodes (3 to 6 digits, e.g., 192, 100, 123, 700)
        boolean isCarrierShortcode = digitsOnly.length() >= 3 && digitsOnly.length() <= 6;

        if (!isOfficialTelecomName && !isCarrierShortcode) {
            Log.w("OskarSmsSecurity", "SMS Injection blocked: sender [" + sender + "] is not a recognized telecom carrier shortcode.");
            return false;
        }

        // 4. Content Verification: Must contain carrier receipt transaction indicators
        String lowerBody = body.toLowerCase(Locale.ROOT);
        boolean hasPaymentKeywords =
            lowerBody.contains("ka heshay") ||
            lowerBody.contains("heshay") ||
            lowerBody.contains("ayaad") ||
            lowerBody.contains("waxaad") ||
            lowerBody.contains("haraagaagu") ||
            lowerBody.contains("haraagagu") ||
            lowerBody.contains("haraaga") ||
            lowerBody.contains("lacag") ||
            lowerBody.contains("received") ||
            lowerBody.contains("evcplus") ||
            lowerBody.contains("evc-plus") ||
            lowerBody.contains("evc plus") ||
            lowerBody.contains("waafi") ||
            lowerBody.contains("zaad") ||
            lowerBody.contains("sahal") ||
            lowerBody.contains("somnet") ||
            lowerBody.contains("jeeb") ||
            lowerBody.contains("edahab") ||
            lowerBody.contains("e-dahab") ||
            lowerBody.contains("premier");

        return hasPaymentKeywords;
    }

    /**
     * Filters candidate SMS to ensure only legitimate carrier payment receipts are processed.
     */
    public static boolean isCandidateSms(String sender, String body) {
        return isLegitimateCarrierSender(sender, body);
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
     * Removes a fingerprint from processed cache so transient network/auth failures can be retried cleanly.
     */
    public static synchronized void unmark(Context context, String fingerprint) {
        if (fingerprint == null) return;
        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            Set<String> set = new HashSet<>(prefs.getStringSet(KEY_PROCESSED_HASHES, new HashSet<>()));
            if (set.remove(fingerprint)) {
                prefs.edit().putStringSet(KEY_PROCESSED_HASHES, set).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in unmark: " + e.getMessage());
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
