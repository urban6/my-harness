package com.example.order.domain;

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
    private long stock;

    @Column(name = "reserved", nullable = false)
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

    public Long getId() { return id; }
    public String getName() { return name; }
    public long getPrice() { return price; }
    public long getStock() { return stock; }
    public long getReserved() { return reserved; }
    public long getAvailable() { return stock - reserved; }
    public Instant getCreatedAt() { return createdAt; }
}
