package com.example.order.orders;

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

    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<OrderLine> lines = new ArrayList<>();

    @Column(length = 20)
    private String couponCode;

    @Column(nullable = false)
    private long subtotal;

    @Column(nullable = false)
    private long discount;

    @Column(nullable = false)
    private long totalPrice;

    @Column(length = 100)
    private String paymentId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant paidAt;

    protected Order() {
    }

    public Order(String userId, List<OrderLine> lines, String couponCode, long discount,
                 Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.lines = new ArrayList<>(lines);
        this.couponCode = couponCode;
        this.subtotal = subtotalOf(lines);
        this.discount = discount;
        this.totalPrice = subtotal - discount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static long subtotalOf(List<OrderLine> lines) {
        return lines.stream().mapToLong(OrderLine::amount).reduce(0L, Math::addExact);
    }

    /** 결제를 시작할 수 있는지: 결제 대기 중이고 만료 전이어야 한다(R5.2). */
    public void ensurePayable(Instant now) {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw invalidState("결제");
        }
        if (isExpiredAt(now)) {
            throw new InvalidOrderStateException("결제 기한이 지난 주문입니다: id=" + id);
        }
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void markPaid(String paymentId, Instant now) {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, "결제");
        this.paymentId = paymentId;
        this.paidAt = now;
    }

    public void markPaymentFailed() {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_FAILED, "결제 거절");
    }

    public void expire() {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED, "만료");
    }

    public void cancel() {
        transition(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "취소");
    }

    public void refund() {
        transition(OrderStatus.PAID, OrderStatus.REFUNDED, "환불");
    }

    public void ship() {
        transition(OrderStatus.PAID, OrderStatus.SHIPPED, "배송");
    }

    public void deliver() {
        transition(OrderStatus.SHIPPED, OrderStatus.DELIVERED, "배송 완료");
    }

    private void transition(OrderStatus from, OrderStatus to, String action) {
        if (status != from) {
            throw invalidState(action);
        }
        status = to;
    }

    private InvalidOrderStateException invalidState(String action) {
        return new InvalidOrderStateException(
                "현재 상태(" + status + ")에서는 " + action + "할 수 없습니다: id=" + id);
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

    public List<OrderLine> getLines() {
        return List.copyOf(lines);
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
