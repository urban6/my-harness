package com.example.order.ordering;

import com.example.order.common.error.InvalidOrderStateException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50, updatable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    /** 연관관계 없이 문자열. 쿠폰 행 락은 리포지토리로 따로 잡는다. */
    @Column(name = "coupon_code", length = 20, updatable = false)
    private String couponCode;

    @Column(name = "subtotal", nullable = false, updatable = false)
    private long subtotal;

    @Column(name = "discount", nullable = false, updatable = false)
    private long discount;

    @Column(name = "total_price", nullable = false, updatable = false)
    private long totalPrice;

    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<OrderLine> items = new ArrayList<>();

    protected Order() {
    }

    public Order(String userId, String couponCode, List<OrderLine> items, long subtotal, long discount,
            Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.couponCode = couponCode;
        this.items = new ArrayList<>(items);
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.updatedAt = createdAt;
    }

    // ---- 상태 전이 (허용되지 않는 전이는 409 INVALID_STATE) ----

    public void markPaid(Instant now, String paymentId) {
        require(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.PAID;
        this.paidAt = now;
        this.paymentId = paymentId;
        this.updatedAt = now;
    }

    public void markPaymentFailed(Instant now, String paymentId) {
        require(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.PAYMENT_FAILED;
        this.paymentId = paymentId;
        this.updatedAt = now;
    }

    public void expire(Instant now) {
        require(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.EXPIRED;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        require(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = now;
    }

    public void refund(Instant now) {
        require(OrderStatus.PAID);
        this.status = OrderStatus.REFUNDED;
        this.updatedAt = now;
    }

    public void ship(Instant now) {
        require(OrderStatus.PAID);
        this.status = OrderStatus.SHIPPED;
        this.updatedAt = now;
    }

    public void deliver(Instant now) {
        require(OrderStatus.SHIPPED);
        this.status = OrderStatus.DELIVERED;
        this.updatedAt = now;
    }

    private void require(OrderStatus expected) {
        if (status != expected) {
            throw new InvalidOrderStateException(
                    "주문 " + id + "의 현재 상태(" + status + ")에서는 처리할 수 없습니다.");
        }
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
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

    public List<OrderLine> getItems() {
        return items;
    }
}
