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

    /** 판매되지 않은 보유 수량. */
    @Column(nullable = false)
    private int stock;

    /** 결제 대기 주문이 잡아 둔 수량. */
    @Column(nullable = false)
    private int reserved;

    @Column(nullable = false, updatable = false)
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

    public boolean canReserve(int quantity) {
        return available() >= quantity;
    }

    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("available stock is not enough: product=" + id);
        }
        reserved += quantity;
    }

    /** 결제 대기가 끝나지 않고 종료됨(취소·만료·결제 거절): 예약만 푼다. */
    public void release(int quantity) {
        reserved -= quantity;
    }

    /** 결제 승인: 예약분을 판매로 확정한다. */
    public void commitSale(int quantity) {
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
