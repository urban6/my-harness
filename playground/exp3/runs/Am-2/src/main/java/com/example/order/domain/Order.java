package com.example.order.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userId;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private String couponCode;
    private long subtotal;
    private long discount;
    private long totalPrice;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant paidAt;
    private String paymentId;
    private String paymentKey;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(String userId, String couponCode, long subtotal, long discount, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void addItem(Long productId, int quantity, long unitPrice) {
        items.add(new OrderItem(this, productId, quantity, unitPrice));
    }

    /** Items ordered by product id, the global lock order for product rows. */
    public List<OrderItem> itemsInLockOrder() {
        return items.stream().sorted(Comparator.comparing(OrderItem::getProductId)).toList();
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public String getCouponCode() { return couponCode; }
    public long getSubtotal() { return subtotal; }
    public long getDiscount() { return discount; }
    public long getTotalPrice() { return totalPrice; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public String getPaymentKey() { return paymentKey; }
    public void setPaymentKey(String paymentKey) { this.paymentKey = paymentKey; }
    public List<OrderItem> getItems() { return items; }
}
