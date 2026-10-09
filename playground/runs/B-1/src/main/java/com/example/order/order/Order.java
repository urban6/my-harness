package com.example.order.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private long totalPrice;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(Instant createdAt) {
        this.status = OrderStatus.ORDERED;
        this.createdAt = createdAt;
    }

    /** unitPrice 는 주문 시점의 상품 가격. totalPrice 는 Σ(unitPrice × quantity). */
    public void addItem(Long productId, int quantity, long unitPrice) {
        items.add(new OrderItem(this, productId, quantity, unitPrice));
        totalPrice = Math.addExact(totalPrice, Math.multiplyExact(unitPrice, quantity));
    }

    public void cancel() {
        if (status == OrderStatus.CANCELLED) {
            throw new OrderAlreadyCancelledException(id);
        }
        status = OrderStatus.CANCELLED;
    }

    public Long getId() {
        return id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalPrice() {
        return totalPrice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
