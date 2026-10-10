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

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private int reserved;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Product() {
    }

    public Product(String name, long price, int stock, Instant createdAt) {
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.reserved = 0;
        this.createdAt = createdAt;
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

    public int getStock() {
        return stock;
    }

    public int getReserved() {
        return reserved;
    }

    public int available() {
        return stock - reserved;
    }

    /** 주문 생성: 예약 증가. */
    public void reserve(int quantity) {
        this.reserved += quantity;
    }

    /** 예약 해제(결제 실패·만료·취소). */
    public void release(int quantity) {
        this.reserved -= quantity;
    }

    /** 결제 승인: 재고 확정 판매. */
    public void commitSale(int quantity) {
        this.stock -= quantity;
        this.reserved -= quantity;
    }

    /** 환불: 재고 복구. */
    public void restock(int quantity) {
        this.stock += quantity;
    }
}
