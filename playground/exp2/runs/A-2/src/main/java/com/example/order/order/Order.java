package com.example.order.order;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    /** PG 호출 중 표시가 이 시간보다 오래되면 비정상 종료로 보고 무시한다. */
    static final Duration PENDING_OPERATION_STALE_AFTER = Duration.ofSeconds(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @ElementCollection
    @CollectionTable(name = "order_item", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    @BatchSize(size = 100)
    private List<OrderLine> items = new ArrayList<>();

    @Column(name = "coupon_code")
    private String couponCode;

    @Column(nullable = false)
    private long subtotal;

    @Column(nullable = false)
    private long discount;

    @Column(name = "total_price", nullable = false)
    private long totalPrice;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "payment_id")
    private String paymentId;

    /** 진행 중인 PG 호출(PAYING/REFUNDING). 그동안 다른 상태 전이를 막는다. */
    @Column(name = "pending_operation")
    private String pendingOperation;

    @Column(name = "pending_operation_at")
    private Instant pendingOperationAt;

    protected Order() {
    }

    public Order(String userId, List<OrderLine> items, String couponCode, long subtotal, long discount,
                 Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.items = new ArrayList<>(items);
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean hasPendingOperation(Instant now) {
        return pendingOperation != null
                && pendingOperationAt.plus(PENDING_OPERATION_STALE_AFTER).isAfter(now);
    }

    public void startOperation(String operation, Instant now) {
        this.pendingOperation = operation;
        this.pendingOperationAt = now;
    }

    public void finishOperation() {
        this.pendingOperation = null;
        this.pendingOperationAt = null;
    }

    public void markPaid(String paymentId, Instant now) {
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.paidAt = now;
    }

    public void changeStatus(OrderStatus status) {
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

    public List<OrderLine> getItems() {
        return items;
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
}
