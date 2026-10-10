package com.example.order.order;

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
import java.util.List;
import org.hibernate.annotations.BatchSize;

/** JPQL 예약어 충돌을 피하려 엔티티명을 OrderEntity 로 둔다. */
@Entity(name = "OrderEntity")
@Table(name = "orders")
public class OrderEntity {

    /** PG 호출 선점 표식의 유효 시간(크래시 방어). */
    public static final Duration GATEWAY_INFLIGHT_TTL = Duration.ofSeconds(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "coupon_id")
    private Long couponId;

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

    @Column(name = "gateway_inflight_since")
    private Instant gatewayInflightSince;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("lineNo ASC")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    protected OrderEntity() {
    }

    public OrderEntity(String userId, Long couponId, String couponCode, long subtotal, long discount,
                       Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.couponId = couponId;
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.updatedAt = createdAt;
    }

    public void addItem(Long productId, int quantity, long unitPrice, int lineNo) {
        items.add(new OrderItem(this, productId, quantity, unitPrice, lineNo));
    }

    /** PG 호출이 (트랜잭션 밖에서) 진행 중임을 나타내는 유효한 표식이 있는가. */
    public boolean hasActiveGatewayCall(Instant now) {
        return gatewayInflightSince != null && gatewayInflightSince.isAfter(now.minus(GATEWAY_INFLIGHT_TTL));
    }

    /** expires_at <= now */
    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void changeStatus(OrderStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    public void markPaid(String paymentId, Instant now) {
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.paidAt = now;
        this.updatedAt = now;
    }

    public void beginGatewayCall(Instant now) {
        this.gatewayInflightSince = now;
        this.updatedAt = now;
    }

    public void clearGatewayCall() {
        this.gatewayInflightSince = null;
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

    public Long getCouponId() {
        return couponId;
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

    public List<OrderItem> getItems() {
        return items;
    }
}
