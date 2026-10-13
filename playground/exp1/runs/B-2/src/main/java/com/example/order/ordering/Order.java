package com.example.order.ordering;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private long totalPrice;

    // PostgreSQL timestamptz 정밀도(마이크로초)에 맞춰 저장 전후 값이 같도록 자른다.
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // 목록 조회 시 N+1 대신 주문 묶음 단위로 항목을 IN 조회한다.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public static Order place() {
        Order order = new Order();
        order.status = OrderStatus.ORDERED;
        order.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return order;
    }

    public void addItem(Long productId, int quantity, long unitPrice) {
        items.add(new OrderItem(this, productId, quantity, unitPrice));
        totalPrice = Math.addExact(totalPrice, Math.multiplyExact(unitPrice, (long) quantity));
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
