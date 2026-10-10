package com.example.order.order;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    @Column(length = 20)
    private String couponCode;

    @Column(nullable = false)
    private long subtotal;

    @Column(nullable = false)
    private long discount;

    @Column(nullable = false)
    private long totalPrice;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant paidAt;

    private String paymentId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(String userId, String couponCode, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.couponCode = couponCode;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void addItem(Long productId, int quantity, long unitPrice) {
        items.add(new OrderItem(this, productId, quantity, unitPrice));
        subtotal = Math.addExact(subtotal, items.getLast().lineTotal());
        totalPrice = subtotal - discount;
    }

    public void applyDiscount(long discount) {
        this.discount = discount;
        this.totalPrice = subtotal - discount;
    }

    public boolean isPaymentExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void markPaid(Instant paidAt, String paymentId) {
        requireStatus(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.PAID;
        this.paidAt = paidAt;
        this.paymentId = paymentId;
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

    private void transition(OrderStatus from, OrderStatus to) {
        requireStatus(from);
        this.status = to;
    }

    private void requireStatus(OrderStatus expected) {
        if (status != expected) {
            throw invalidState(this);
        }
    }

    public static BusinessException invalidState(Order order) {
        return new BusinessException(ErrorCode.INVALID_STATE,
                "현재 주문 상태에서 처리할 수 없습니다: id=" + order.id + ", status=" + order.status);
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
