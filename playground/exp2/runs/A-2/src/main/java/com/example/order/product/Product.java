package com.example.order.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private long price;

    /** 판매되지 않은 보유 수량. */
    @Column(nullable = false)
    private int stock;

    /** 결제 대기 주문이 잡아 둔 수량. */
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
        this.createdAt = createdAt;
    }

    public int available() {
        return stock - reserved;
    }

    public void reserve(int quantity) {
        reserved += quantity;
    }

    public void releaseReservation(int quantity) {
        reserved -= quantity;
    }

    /** 결제 승인: 예약분을 판매 처리한다. */
    public void sellReserved(int quantity) {
        reserved -= quantity;
        stock -= quantity;
    }

    /** 환불: 판매분을 재고로 되돌린다. */
    public void restock(int quantity) {
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

    public int getStock() {
        return stock;
    }

    public int getReserved() {
        return reserved;
    }
}
