package so.oskarshop.sms.model;

import com.google.gson.annotations.SerializedName;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SmsLogEntry {
    @SerializedName("id")
    private String id;

    @SerializedName("timestamp")
    private long timestamp;

    @SerializedName("sender")
    private String sender;

    @SerializedName("rawMessage")
    private String rawMessage;

    @SerializedName("amount")
    private Double amount;

    @SerializedName("payerPhone")
    private String payerPhone;

    @SerializedName("status")
    private String status; // "MATCHED", "RECEIVED", "FAILED"

    @SerializedName("matchedOrderId")
    private String matchedOrderId;

    @SerializedName("responseMessage")
    private String responseMessage;

    public SmsLogEntry() {
        this.timestamp = System.currentTimeMillis();
        this.id = "log_" + this.timestamp;
    }

    public SmsLogEntry(String sender, String rawMessage, Double amount, String payerPhone, String status, String matchedOrderId, String responseMessage) {
        this();
        this.sender = sender;
        this.rawMessage = rawMessage;
        this.amount = amount;
        this.payerPhone = payerPhone;
        this.status = status;
        this.matchedOrderId = matchedOrderId;
        this.responseMessage = responseMessage;
    }

    public String getId() { return id; }
    public long getTimestamp() { return timestamp; }
    public String getSender() { return sender; }
    public String getRawMessage() { return rawMessage; }
    public Double getAmount() { return amount; }
    public String getPayerPhone() { return payerPhone; }
    public String getStatus() { return status; }
    public String getMatchedOrderId() { return matchedOrderId; }
    public String getResponseMessage() { return responseMessage; }

    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US);
        return sdf.format(new Date(timestamp));
    }
}
