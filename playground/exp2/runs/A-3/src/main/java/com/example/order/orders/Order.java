package com.example.order.orders;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "coupon_code", length = 20)
    private String couponCode;

    @Column(nullable = false)
    private long subtotal;

    @Column(nullable = false)
    private long discount;

    @Column(name = "total_price", nullable = false)
    private long totalPrice;

    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("lineNo")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(String userId, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void addItem(long productId, int quantity, long unitPrice) {
        items.add(new OrderItem(this, items.size(), productId, quantity, unitPrice));
        subtotal = Math.addExact(subtotal, Math.multiplyExact(unitPrice, (long) quantity));
        totalPrice = subtotal - discount;
    }

    public void applyCoupon(String couponCode, long discount) {
        this.couponCode = couponCode;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
    }

    public boolean isPaymentOverdue(Instant now) {
        return status == OrderStatus.PENDING_PAYMENT && !now.isBefore(expiresAt);
    }

    public void markPaid(String paymentId, Instant paidAt) {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID);
        this.paymentId = paymentId;
        this.paidAt = paidAt;
    }

    public void markPaymentFailed() {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_FAILED);
    }

    public void markExpired() {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED);
    }

    public void markCancelled() {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED);
    }

    public void markRefunded() {
        transition(OrderStatus.PAID, OrderStatus.REFUNDED);
    }

    public void markShipped() {
        transition(OrderStatus.PAID, OrderStatus.SHIPPED);
    }

    public void markDelivered() {
        transition(OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    private void transition(OrderStatus from, OrderStatus to) {
        if (status != from) {
            throw new IllegalStateException("order " + id + " is " + status + ", expected " + from);
        }
        status = to;
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

    public String getPaymentId() {
        return paymentId;
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

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
