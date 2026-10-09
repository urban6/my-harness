package com.example.order.order;

import com.example.order.common.error.InvalidStateException;
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

    @Column(name = "pg_payment_id", length = 100)
    private String pgPaymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("lineNo ASC")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public static Order create(String userId, String couponCode, long subtotal, long discount,
                               Instant createdAt, Instant expiresAt) {
        Order o = new Order();
        o.userId = userId;
        o.status = OrderStatus.PENDING_PAYMENT;
        o.couponCode = couponCode;
        o.subtotal = subtotal;
        o.discount = discount;
        o.totalPrice = subtotal - discount;
        o.createdAt = createdAt;
        o.expiresAt = expiresAt;
        return o;
    }

    public void addItem(int lineNo, Long productId, int quantity, long unitPrice) {
        items.add(OrderItem.of(this, lineNo, productId, quantity, unitPrice));
    }

    /** 결제 대기 중이고 만료 시각이 지났는가(now ≥ expiresAt). */
    public boolean isExpiredAt(Instant now) {
        return status == OrderStatus.PENDING_PAYMENT && !now.isBefore(expiresAt);
    }

    public void markPaid(Instant paidAt, String pgPaymentId) {
        require(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.PAID;
        this.paidAt = paidAt;
        this.pgPaymentId = pgPaymentId;
    }

    public void markPaymentFailed(String pgPaymentId) {
        require(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.PAYMENT_FAILED;
        this.pgPaymentId = pgPaymentId;
    }

    public void cancel() {
        require(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.CANCELLED;
    }

    public void expire() {
        require(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.EXPIRED;
    }

    public void refund() {
        require(OrderStatus.PAID);
        this.status = OrderStatus.REFUNDED;
    }

    public void ship() {
        require(OrderStatus.PAID);
        this.status = OrderStatus.SHIPPED;
    }

    public void deliver() {
        require(OrderStatus.SHIPPED);
        this.status = OrderStatus.DELIVERED;
    }

    private void require(OrderStatus expected) {
        if (status != expected) {
            throw new InvalidStateException(
                    "주문 상태가 " + expected + "이(가) 아닙니다: id=" + id + ", status=" + status);
        }
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

    public String getPgPaymentId() {
        return pgPaymentId;
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
        return items;
    }
}
