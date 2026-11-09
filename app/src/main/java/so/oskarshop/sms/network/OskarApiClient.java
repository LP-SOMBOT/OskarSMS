package so.oskarshop.sms.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OskarApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;

    public OskarApiClient() {
        this.client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    }

    public static class ForwardResult {
        public final boolean success;
        public final int httpCode;
        public final String status; // "matched", "unmatched", "duplicate", "ignored", "error"
        public final String matchedOrderId;
        public final Double amount;
        public final String phone;
        public final String message;
        public final String rawResponse;

        public ForwardResult(boolean success, int httpCode, String status, String matchedOrderId, Double amount, String phone, String message, String rawResponse) {
            this.success = success;
            this.httpCode = httpCode;
            this.status = status;
            this.matchedOrderId = matchedOrderId;
            this.amount = amount;
            this.phone = phone;
            this.message = message;
            this.rawResponse = rawResponse;
        }
    }

    public ForwardResult forwardSms(String primaryUrl, String fallbackUrl, String secretToken, String sender, String content) {
        // 1. Try primary URL
        ForwardResult primary = executePost(primaryUrl, secretToken, sender, content);
        if (primary.success) {
            return primary;
        }

        // 2. If primary fails and fallback exists, try fallback URL
        if (fallbackUrl != null && !fallbackUrl.isEmpty() && !fallbackUrl.equalsIgnoreCase(primaryUrl)) {
            ForwardResult fallback = executePost(fallbackUrl, secretToken, sender, content);
            if (fallback.success) {
                return fallback;
            }
        }

        return primary;
    }

    private ForwardResult executePost(String targetUrl, String secretToken, String sender, String content) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("from", sender);
            json.addProperty("content", content);

            RequestBody body = RequestBody.create(json.toString(), JSON);

            Request request = new Request.Builder()
                .url(targetUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-forwarder-token", secretToken)
                .addHeader("User-Agent", "OskarSMS-Android/1.0.1")
                .post(body)
                .build();

            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                String resStr = response.body() != null ? response.body().string() : "";

                if (response.isSuccessful()) {
                    try {
                        JsonObject obj = JsonParser.parseString(resStr).getAsJsonObject();
                        String status = obj.has("status") ? obj.get("status").getAsString() : "received";
                        String matchedOrderId = obj.has("matchedOrderId") && !obj.get("matchedOrderId").isJsonNull()
                            ? obj.get("matchedOrderId").getAsString() : null;
                        Double amount = obj.has("amount") && !obj.get("amount").isJsonNull()
                            ? obj.get("amount").getAsDouble() : null;
                        String phone = obj.has("phone") && !obj.get("phone").isJsonNull()
                            ? obj.get("phone").getAsString() : null;
                        String msg = obj.has("message") && !obj.get("message").isJsonNull()
                            ? obj.get("message").getAsString() : "Processed by OskarShop";

                        return new ForwardResult(true, code, status, matchedOrderId, amount, phone, msg, resStr);
                    } catch (Exception parseEx) {
                        return new ForwardResult(true, code, "received", null, null, null, "Received OK", resStr);
                    }
                } else {
                    return new ForwardResult(false, code, "error", null, null, null, "Server HTTP " + code, resStr);
                }
            }
        } catch (IOException e) {
            return new ForwardResult(false, 0, "network_error", null, null, null, "Network error: " + e.getMessage(), null);
        } catch (Exception e) {
            return new ForwardResult(false, 0, "client_error", null, null, null, "Error: " + e.getMessage(), null);
        }
    }

    public boolean pingServer(String targetUrl, String secretToken) {
        try {
            Request request = new Request.Builder()
                .url(targetUrl)
                .addHeader("x-forwarder-token", secretToken)
                .addHeader("User-Agent", "OskarSMS-Android/1.0.1")
                .get()
                .build();

            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
