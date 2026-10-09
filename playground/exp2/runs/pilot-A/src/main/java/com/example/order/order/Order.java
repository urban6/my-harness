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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hibernate.annotations.BatchSize;

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

    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(String userId, List<OrderItem> items, String couponCode, long subtotal, long discount,
                 Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.items = new ArrayList<>(items);
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isPaymentDeadlinePassed(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** 데드락을 피하기 위해 상품 행은 항상 id 오름차순으로 잠근다. */
    public List<OrderItem> itemsInLockOrder() {
        return items.stream().sorted(Comparator.comparingLong(OrderItem::getProductId)).toList();
    }

    void markPaid(String paymentId, Instant paidAt) {
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.paidAt = paidAt;
    }

    void changeStatus(OrderStatus status) {
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
        return items;
    }
}
