package com.example.order.domain;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.BatchSize;

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
    private String idempotencyKey;
    private String requestHash;
    private String payIdempotencyKey;
    private String paymentId;
    private Instant paymentStartedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(String userId, String idempotencyKey, String requestHash, String couponCode,
                 long subtotal, long discount, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = OrderStatus.PENDING_PAYMENT;
    }

    public void addItem(long productId, int quantity, long unitPrice) {
        items.add(new OrderItem(this, productId, quantity, unitPrice));
    }

    /** True while a payment/refund call to the gateway may still be running for this order. */
    public boolean isProcessing(Instant now, Duration lockTimeout) {
        return paymentStartedAt != null && paymentStartedAt.isAfter(now.minus(lockTimeout));
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void beginProcessing(String payKey, Instant now) {
        if (payKey != null) {
            this.payIdempotencyKey = payKey;
        }
        this.paymentStartedAt = now;
    }

    public void endProcessing() {
        this.paymentStartedAt = null;
    }

    /** Gateway call failed without a verdict: forget the pay attempt so it can be retried. */
    public void abortProcessing(boolean clearPayKey) {
        this.paymentStartedAt = null;
        if (clearPayKey) {
            this.payIdempotencyKey = null;
        }
    }

    public void changeStatus(OrderStatus status) {
        this.status = status;
        this.paymentStartedAt = null;
    }

    public void markPaid(String paymentId, Instant now) {
        this.paymentId = paymentId;
        this.paidAt = now;
        changeStatus(OrderStatus.PAID);
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public String getCouponCode() { return couponCode; }
    public long getSubtotal() { return subtotal; }
    public long getDiscount() { return discount; }
    public long getTotalPrice() { return totalPrice; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getPaidAt() { return paidAt; }
    public String getRequestHash() { return requestHash; }
    public String getPayIdempotencyKey() { return payIdempotencyKey; }
    public String getPaymentId() { return paymentId; }
    public List<OrderItem> getItems() { return items; }
}
