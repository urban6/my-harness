package com.example.order.domain;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hibernate.annotations.BatchSize;

/** 엔티티 이름은 HQL 예약어 충돌을 피하려고 PurchaseOrder 로 둔다. 테이블은 orders. */
@Entity(name = "PurchaseOrder")
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "coupon_id")
    private Long couponId;

    @Column(name = "coupon_code", length = 20)
    private String couponCode;

    @Column(name = "subtotal", nullable = false)
    private long subtotal;

    @Column(name = "discount", nullable = false)
    private long discount;

    @Column(name = "total_price", nullable = false)
    private long totalPrice;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "payment_id", length = 100)
    private String paymentId;

    @Column(name = "gateway_call_started_at")
    private Instant gatewayCallStartedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(String userId, Long couponId, String couponCode, long subtotal, long discount,
                 Instant createdAt, Duration ttl) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.couponId = couponId;
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.createdAt = createdAt;
        this.expiresAt = createdAt.plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public void addItem(long productId, long quantity, long unitPrice) {
        items.add(new OrderItem(this, productId, quantity, unitPrice));
    }

    /** 락 순서 규칙: 상품은 항상 productId 오름차순으로 만진다. */
    public List<OrderItem> itemsByProductId() {
        List<OrderItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingLong(OrderItem::getProductId));
        return sorted;
    }

    /** 결제/환불 PG 호출이 진행 중인가 (표지가 신선한가). */
    public boolean isGatewayCallInFlight(Instant now, Duration inFlightTimeout) {
        return gatewayCallStartedAt != null && gatewayCallStartedAt.isAfter(now.minus(inFlightTimeout));
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public Long getCouponId() { return couponId; }
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
    public Instant getGatewayCallStartedAt() { return gatewayCallStartedAt; }
    public void setGatewayCallStartedAt(Instant t) { this.gatewayCallStartedAt = t; }
    public List<OrderItem> getItems() { return items; }
}
