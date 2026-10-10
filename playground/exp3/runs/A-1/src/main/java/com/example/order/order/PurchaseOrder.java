package com.example.order.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class PurchaseOrder {

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
    private String idempotencyKey;
    private String requestHash;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    protected PurchaseOrder() {
    }

    PurchaseOrder(String userId, String couponCode, long subtotal, long discount, Instant createdAt,
            Instant expiresAt, String idempotencyKey, String requestHash) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    void addItem(long productId, long quantity, long unitPrice) {
        items.add(new OrderItem(this, productId, quantity, unitPrice));
    }

    boolean isExpiredAt(Instant now) {
        return status == OrderStatus.PENDING_PAYMENT && !expiresAt.isAfter(now);
    }

    void markPaid(String paymentId, String paymentKey, Instant now) {
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.paymentKey = paymentKey;
        this.paidAt = now;
    }

    void markPaymentFailed(String paymentId, String paymentKey) {
        this.status = OrderStatus.PAYMENT_FAILED;
        this.paymentId = paymentId;
        this.paymentKey = paymentKey;
    }

    void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public long getSubtotal() {
        return subtotal;
    }

    public long getDiscount() {
        return discount;
    }

    public long getTotalPrice() {
        return totalPrice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getPaymentKey() {
        return paymentKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
