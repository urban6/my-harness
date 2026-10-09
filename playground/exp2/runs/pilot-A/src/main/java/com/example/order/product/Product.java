package com.example.order.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private long stock;

    @Column(nullable = false)
    private long reserved;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Product() {
    }

    public Product(String name, long price, long stock, Instant createdAt) {
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.reserved = 0;
        this.createdAt = createdAt;
    }

    public long available() {
        return stock - reserved;
    }

    /** 결제 대기 주문을 위해 수량을 잡아 둔다. */
    public void reserve(long quantity) {
        if (available() < quantity) {
            throw new IllegalStateException("insufficient stock for product " + id);
        }
        reserved += quantity;
    }

    /** 잡아 둔 수량을 되돌린다 (취소·만료·결제 거절). */
    public void releaseReservation(long quantity) {
        reserved -= quantity;
    }

    /** 결제 승인: 잡아 둔 수량이 판매되어 보유 수량에서 빠진다. */
    public void confirmSale(long quantity) {
        reserved -= quantity;
        stock -= quantity;
    }

    /** 환불: 판매된 수량을 보유 수량으로 되돌린다. */
    public void restock(long quantity) {
        stock += quantity;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public long getStock() {
        return stock;
    }

    public long getReserved() {
        return reserved;
    }
}
