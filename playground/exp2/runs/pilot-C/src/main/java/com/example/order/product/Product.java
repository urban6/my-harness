package com.example.order.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "name", nullable = false)
    private String name;
    private long price;
    private int stock;
    private int reserved;
    private OffsetDateTime createdAt;

    protected Product() {
    }

    public Product(String name, long price, int stock, OffsetDateTime createdAt) {
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.reserved = 0;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public long getPrice() { return price; }
    public int getStock() { return stock; }
    public int getReserved() { return reserved; }
    public int available() { return stock - reserved; }

    public void reserve(int q) { this.reserved += q; }
    public void release(int q) { this.reserved -= q; }
    /** 결제 승인: 판매 확정. */
    public void sell(int q) { this.stock -= q; this.reserved -= q; }
    /** 환불: 재고 복원. */
    public void restock(int q) { this.stock += q; }
}
