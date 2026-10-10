package com.example.order.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import com.example.order.common.error.DomainException;
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
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

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

    private String payIdempotencyKey;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() { }

    public Order(String userId, String idempotencyKey, String requestFingerprint, List<OrderItem> items,
                 String couponCode, long subtotal, long discount, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.items = new ArrayList<>(items);
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = OrderStatus.PENDING_PAYMENT;
    }

    public boolean isExpiredAt(Instant now) {
        return status == OrderStatus.PENDING_PAYMENT && !expiresAt.isAfter(now);
    }

    /** 재고 갱신을 productId 오름차순(데드락 방지)으로 하기 위한 상품별 수량. */
    public SortedMap<Long, Integer> quantitiesByProduct() {
        SortedMap<Long, Integer> quantities = new TreeMap<>();
        for (OrderItem item : items) {
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return quantities;
    }

    public void requireStatus(OrderStatus... allowed) {
        if (!Arrays.asList(allowed).contains(status)) {
            throw DomainException.conflict("INVALID_ORDER_STATE",
                    "현재 주문 상태(" + status + ")에서는 처리할 수 없습니다.");
        }
    }

    public void markPaid(String paymentId, String payIdempotencyKey, Instant now) {
        requireStatus(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.payIdempotencyKey = payIdempotencyKey;
        this.paidAt = now;
    }

    public void markPaymentFailed(String payIdempotencyKey) {
        requireStatus(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.PAYMENT_FAILED;
        this.payIdempotencyKey = payIdempotencyKey;
    }

    public void expire() {
        requireStatus(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.EXPIRED;
    }

    public void cancel() {
        requireStatus(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.CANCELLED;
    }

    public void refund() {
        requireStatus(OrderStatus.PAID);
        this.status = OrderStatus.REFUNDED;
    }

    public void ship() {
        requireStatus(OrderStatus.PAID);
        this.status = OrderStatus.SHIPPED;
    }

    public void deliver() {
        requireStatus(OrderStatus.SHIPPED);
        this.status = OrderStatus.DELIVERED;
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public OrderStatus getStatus() { return status; }
    public String getCouponCode() { return couponCode; }
    public long getSubtotal() { return subtotal; }
    public long getDiscount() { return discount; }
    public long getTotalPrice() { return totalPrice; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getPaidAt() { return paidAt; }
    public String getPaymentId() { return paymentId; }
    public String getPayIdempotencyKey() { return payIdempotencyKey; }
    public List<OrderItem> getItems() { return List.copyOf(items); }
}
