package so.oskarshop.sms.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import so.oskarshop.sms.model.OrderItem;

public class OskarApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;

    public OskarApiClient() {
        this.client = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    }

    public static boolean isNetworkAvailable(Context context) {
        if (context == null) return true;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network nw = cm.getActiveNetwork();
                if (nw == null) return false;
                NetworkCapabilities actNw = cm.getNetworkCapabilities(nw);
                return actNw != null && (
                    actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                );
            } else {
                NetworkInfo nwInfo = cm.getActiveNetworkInfo();
                return nwInfo != null && nwInfo.isConnected();
            }
        } catch (Exception e) {
            return true;
        }
    }

    public static class LoginResult {
        public final boolean success;
        public final String message;
        public final String token;
        public final String secretToken;
        public final String adminName;
        public final String phone;

        public LoginResult(boolean success, String message, String token, String secretToken, String adminName, String phone) {
            this.success = success;
            this.message = message;
            this.token = token;
            this.secretToken = secretToken;
            this.adminName = adminName;
            this.phone = phone;
        }
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

    public LoginResult loginAdmin(String serverUrl, String phone, String password) {
        try {
            String baseUrl = serverUrl != null ? serverUrl.replace("/api/sms-webhook", "") : "https://www.oskarshop.so";
            if (baseUrl.startsWith("https://oskarshop.so")) {
                baseUrl = baseUrl.replace("https://oskarshop.so", "https://www.oskarshop.so");
            }
            String loginUrl = baseUrl + "/api/admin/apk-login";
            JsonObject json = new JsonObject();
            json.addProperty("phone", phone);
            json.addProperty("password", password);
            json.addProperty("appVersion", "1.2.2");

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder()
                .url(loginUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "OskarSMS-Android/1.2.2")
                .post(body)
                .build();

            try (Response response = client.newCall(request).execute()) {
                String resStr = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    JsonObject obj = JsonParser.parseString(resStr).getAsJsonObject();
                    boolean ok = obj.has("success") && obj.get("success").getAsBoolean();
                    String msg = obj.has("message") ? obj.get("message").getAsString() : "Success";
                    String token = obj.has("token") && !obj.get("token").isJsonNull() ? obj.get("token").getAsString() : "";
                    String secret = obj.has("secretToken") && !obj.get("secretToken").isJsonNull() ? obj.get("secretToken").getAsString() : "";

                    String adminName = "Admin";
                    String resPhone = phone;
                    if (obj.has("admin") && obj.get("admin").isJsonObject()) {
                        JsonObject adm = obj.getAsJsonObject("admin");
                        if (adm.has("name") && !adm.get("name").isJsonNull()) adminName = adm.get("name").getAsString();
                        if (adm.has("phone") && !adm.get("phone").isJsonNull()) resPhone = adm.get("phone").getAsString();
                    }

                    if (ok) {
                        return new LoginResult(true, msg, token, secret, adminName, resPhone);
                    } else {
                        return new LoginResult(false, msg, null, null, null, null);
                    }
                } else {
                    String msg = "Login failed (HTTP " + response.code() + ")";
                    try {
                        JsonObject obj = JsonParser.parseString(resStr).getAsJsonObject();
                        if (obj.has("message")) msg = obj.get("message").getAsString();
                    } catch (Exception ignored) {}
                    return new LoginResult(false, msg, null, null, null, null);
                }
            }
        } catch (Exception e) {
            return new LoginResult(false, "Connection error: " + e.getMessage(), null, null, null, null);
        }
    }

    public ForwardResult forwardSms(String primaryUrl, String fallbackUrl, String secretToken, String adminToken, String adminPhone, String sender, String content) {
        // 1. Try primary URL
        ForwardResult primary = executePost(primaryUrl, secretToken, adminToken, adminPhone, sender, content);
        if (primary.success) {
            return primary;
        }

        // 2. If primary fails and fallback exists, try fallback URL
        if (fallbackUrl != null && !fallbackUrl.isEmpty() && !fallbackUrl.equalsIgnoreCase(primaryUrl)) {
            ForwardResult fallback = executePost(fallbackUrl, secretToken, adminToken, adminPhone, sender, content);
            if (fallback.success) {
                return fallback;
            }
        }

        return primary;
    }

    private ForwardResult executePost(String targetUrl, String secretToken, String adminToken, String adminPhone, String sender, String content) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("from", sender);
            json.addProperty("content", content);

            RequestBody body = RequestBody.create(json.toString(), JSON);

            Request.Builder reqBuilder = new Request.Builder()
                .url(targetUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-forwarder-token", secretToken)
                .addHeader("User-Agent", "OskarSMS-Android/1.2.2");

            if (adminToken != null && !adminToken.isEmpty()) {
                reqBuilder.addHeader("x-admin-token", adminToken);
            }
            if (adminPhone != null && !adminPhone.isEmpty()) {
                reqBuilder.addHeader("x-admin-phone", adminPhone);
            }

            Request request = reqBuilder.post(body).build();

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

    public List<OrderItem> fetchNewOrders(String primaryUrl, String fallbackUrl, String secretToken, String adminToken, String adminPhone, long since) {
        List<OrderItem> list = executeGetOrders(primaryUrl, secretToken, adminToken, adminPhone, since);
        if (list != null) {
            return list;
        }

        if (fallbackUrl != null && !fallbackUrl.isEmpty() && !fallbackUrl.equalsIgnoreCase(primaryUrl)) {
            list = executeGetOrders(fallbackUrl, secretToken, adminToken, adminPhone, since);
            if (list != null) {
                return list;
            }
        }

        // Webhook action=orders fallback
        String webhookOrdersUrl = "https://www.oskarshop.so/api/sms-webhook?action=orders";
        list = executeGetOrders(webhookOrdersUrl, secretToken, adminToken, adminPhone, since);
        return list != null ? list : new ArrayList<>();
    }

    private List<OrderItem> executeGetOrders(String targetUrl, String secretToken, String adminToken, String adminPhone, long since) {
        try {
            String fullUrl = targetUrl + (targetUrl.contains("?") ? "&" : "?") + "since=" + since;
            Request.Builder reqBuilder = new Request.Builder()
                .url(fullUrl)
                .addHeader("x-forwarder-token", secretToken)
                .addHeader("User-Agent", "OskarSMS-Android/1.2.2");
            if (adminToken != null && !adminToken.isEmpty()) {
                reqBuilder.addHeader("x-admin-token", adminToken);
            }
            if (adminPhone != null && !adminPhone.isEmpty()) {
                reqBuilder.addHeader("x-admin-phone", adminPhone);
            }
            Request request = reqBuilder.get().build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }

                String resStr = response.body().string();
                JsonObject obj = JsonParser.parseString(resStr).getAsJsonObject();
                if (!obj.has("orders") || !obj.get("orders").isJsonArray()) {
                    return null;
                }

                List<OrderItem> orders = new ArrayList<>();
                JsonArray arr = obj.getAsJsonArray("orders");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();

                    String id = o.has("id") ? o.get("id").getAsString() : "Order";
                    String title = o.has("title") ? o.get("title").getAsString() : "Free Fire Diamonds";
                    double amount = o.has("amount") ? o.get("amount").getAsDouble() : 0.0;
                    String customerPhone = o.has("customerPhone") ? o.get("customerPhone").getAsString() : "N/A";
                    String customerName = o.has("customerName") ? o.get("customerName").getAsString() : "Customer";
                    String status = o.has("status") ? o.get("status").getAsString() : "pending";
                    long createdAt = o.has("createdAt") ? o.get("createdAt").getAsLong() : System.currentTimeMillis();

                    orders.add(new OrderItem(id, title, amount, customerPhone, customerName, status, createdAt));
                }
                return orders;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public boolean pingServer(String targetUrl, String secretToken, String adminToken, String adminPhone) {
        try {
            Request.Builder reqBuilder = new Request.Builder()
                .url(targetUrl)
                .addHeader("x-forwarder-token", secretToken)
                .addHeader("User-Agent", "OskarSMS-Android/1.2.2");
            if (adminToken != null && !adminToken.isEmpty()) {
                reqBuilder.addHeader("x-admin-token", adminToken);
            }
            if (adminPhone != null && !adminPhone.isEmpty()) {
                reqBuilder.addHeader("x-admin-phone", adminPhone);
            }
            Request request = reqBuilder.get().build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return false;
                }
                String resStr = response.body().string();
                JsonObject obj = JsonParser.parseString(resStr).getAsJsonObject();
                return !obj.has("authenticated") || obj.get("authenticated").getAsBoolean();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
