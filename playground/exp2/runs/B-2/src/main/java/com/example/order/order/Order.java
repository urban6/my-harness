package com.example.order.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
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

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    private String couponCode;

    @Column(nullable = false)
    private long subtotal;

    @Column(nullable = false)
    private long discount;

    @Column(nullable = false)
    private long totalPrice;

    private String paymentId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant paidAt;

    protected Order() {
    }

    public Order(String userId, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void addItem(Long productId, int quantity, long unitPrice) {
        OrderItem item = new OrderItem(this, productId, quantity, unitPrice);
        items.add(item);
        subtotal = Math.addExact(subtotal, item.lineTotal());
        totalPrice = subtotal - discount;
    }

    public void applyCoupon(String couponCode, long discount) {
        this.couponCode = couponCode;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
    }

    public boolean isPaymentExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void markPaid(String paymentId, Instant paidAt) {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID);
        this.paymentId = paymentId;
        this.paidAt = paidAt;
    }

    public void markPaymentFailed() {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_FAILED);
    }

    public void expire() {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED);
    }

    public void cancel() {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED);
    }

    public void refund() {
        transition(OrderStatus.PAID, OrderStatus.REFUNDED);
    }

    public void ship() {
        transition(OrderStatus.PAID, OrderStatus.SHIPPED);
    }

    public void deliver() {
        transition(OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    private void transition(OrderStatus expected, OrderStatus next) {
        if (status != expected) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "주문 상태가 %s이므로 %s(으)로 바꿀 수 없습니다: id=%d".formatted(status, next, id));
        }
        status = next;
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

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
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
}
