package com.example.order.order;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private String requestHash;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    @Column(updatable = false)
    private String couponCode;

    @Column(nullable = false, updatable = false)
    private long subtotal;

    @Column(nullable = false, updatable = false)
    private long discount;

    @Column(nullable = false, updatable = false)
    private long totalPrice;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant paidAt;

    /** 마지막으로 결제를 시도한 Idempotency-Key. 같은 키의 재요청은 현재 주문을 그대로 돌려준다. */
    private String paymentKey;

    private String paymentId;

    /** PG 호출이 진행 중이면 시작 시각. 호출 중에는 만료·취소·중복 결제를 막는다. */
    private Instant gatewayStartedAt;

    protected Order() { }

    public Order(long userId, String idempotencyKey, String requestHash, List<OrderItem> items, String couponCode,
                 long subtotal, long discount, Instant now, Instant expiresAt) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.items = new ArrayList<>(items);
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isGatewayBusy(Instant now, Duration lockTimeout) {
        return gatewayStartedAt != null && gatewayStartedAt.plus(lockTimeout).isAfter(now);
    }

    public void startGatewayCall(Instant now) {
        this.gatewayStartedAt = now;
    }

    public void startPayment(String key, Instant now) {
        this.paymentKey = key;
        this.gatewayStartedAt = now;
    }

    public void endGatewayCall() {
        this.gatewayStartedAt = null;
    }

    public void markPaid(String paymentId, Instant now) {
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.paidAt = now;
    }

    public void changeStatus(OrderStatus status) {
        this.status = status;
    }

    /** 락 순서를 일정하게 유지하기 위해 항상 productId 오름차순으로 돌려준다. */
    public List<OrderItem> getItems() {
        return items.stream().sorted(Comparator.comparingLong(OrderItem::getProductId)).toList();
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public String getRequestHash() { return requestHash; }
    public String getCouponCode() { return couponCode; }
    public long getSubtotal() { return subtotal; }
    public long getDiscount() { return discount; }
    public long getTotalPrice() { return totalPrice; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getPaidAt() { return paidAt; }
    public String getPaymentKey() { return paymentKey; }
    public String getPaymentId() { return paymentId; }
}
