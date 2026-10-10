package com.example.order.product;

import com.example.order.common.error.InsufficientStockException;
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

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "price", nullable = false)
    private long price;

    @Column(name = "stock", nullable = false)
    private int stock;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Column(name = "created_at", nullable = false, updatable = false)
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

    public int available() {
        return stock - reserved;
    }

    public void reserve(int quantity) {
        if (available() < quantity) {
            throw new InsufficientStockException(
                    "상품 " + id + "의 주문 가능 수량(" + available() + ")이 요청 수량(" + quantity + ")보다 적습니다.");
        }
        reserved += quantity;
    }

    public void releaseReservation(int quantity) {
        reserved -= quantity;
    }

    /** 결제 승인: 판매 확정. stock과 reserved가 함께 줄어든다. */
    public void confirmSale(int quantity) {
        stock -= quantity;
        reserved -= quantity;
    }

    /** 환불: 판매되었던 수량이 재고로 돌아온다. */
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
