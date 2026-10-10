package com.example.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private long price;
    private int stock;
    private int reserved;

    protected Product() {
    }

    public Product(String name, long price, int stock) {
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public long getPrice() { return price; }
    public int getStock() { return stock; }
    public int getReserved() { return reserved; }
    public int getAvailable() { return stock - reserved; }
}
