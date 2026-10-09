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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "orders")
public class OrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userId;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private String couponCode;
    private long subtotal;
    private long discount;
    private long totalPrice;
    private String paymentId;
    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime paidAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    protected OrderEntity() {
    }

    public OrderEntity(String userId, String couponCode, long subtotal, long discount, long totalPrice,
                       OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPrice = totalPrice;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void addItem(long productId, int quantity, long unitPrice) {
        items.add(new OrderItem(this, items.size(), productId, quantity, unitPrice));
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public String getCouponCode() { return couponCode; }
    public long getSubtotal() { return subtotal; }
    public long getDiscount() { return discount; }
    public long getTotalPrice() { return totalPrice; }
    public String getPaymentId() { return paymentId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getPaidAt() { return paidAt; }
    public List<OrderItem> getItems() { return items; }

    public void transitionTo(OrderStatus next) { this.status = next; }
    public void markPaid(String paymentId, OffsetDateTime paidAt) {
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.paidAt = paidAt;
    }
    public void markPaymentFailed(String paymentId) {
        this.status = OrderStatus.PAYMENT_FAILED;
        this.paymentId = paymentId;
    }
}
