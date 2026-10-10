package com.example.order.order;

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
public class PurchaseOrder {

    /** PG 호출이 진행 중이라고 보는 최대 시간. 이보다 오래된 표시는 비정상 종료의 잔재로 본다. */
    private static final Duration GATEWAY_CALL_STALE_AFTER = Duration.ofSeconds(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private Long couponId;
    private String couponCode;
    private long subtotal;
    private long discount;
    private long totalPrice;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant paidAt;
    private String paymentId;
    private Instant gatewayStartedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public PurchaseOrder(String userId, Long couponId, String couponCode, long subtotal, long discount,
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
    }

    public void addItem(long productId, int quantity, long unitPrice) {
        items.add(new OrderItem(this, productId, quantity, unitPrice));
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

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isGatewayCallInFlight(Instant now) {
        return gatewayStartedAt != null && gatewayStartedAt.isAfter(now.minus(GATEWAY_CALL_STALE_AFTER));
    }

    public void markGatewayCallStarted(Instant now) {
        this.gatewayStartedAt = now;
    }

    public void clearGatewayCall() {
        this.gatewayStartedAt = null;
    }

    public void changeStatus(OrderStatus next) {
        this.status = next;
    }

    public void markPaid(Instant now, String paymentId) {
        this.status = OrderStatus.PAID;
        this.paidAt = now;
        this.paymentId = paymentId;
    }
}
