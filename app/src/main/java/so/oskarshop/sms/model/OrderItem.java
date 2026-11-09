package so.oskarshop.sms.model;

public class OrderItem {
    public final String id;
    public final String title;
    public final double amount;
    public final String customerPhone;
    public final String customerName;
    public final String status;
    public final long createdAt;

    public OrderItem(String id, String title, double amount, String customerPhone, String customerName, String status, long createdAt) {
        this.id = id;
        this.title = title;
        this.amount = amount;
        this.customerPhone = customerPhone;
        this.customerName = customerName;
        this.status = status;
        this.createdAt = createdAt;
    }
}
