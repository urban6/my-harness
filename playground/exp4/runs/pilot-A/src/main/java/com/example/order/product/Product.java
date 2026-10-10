package com.example.order.product;

import jakarta.persistence.Column;
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

    @Column(nullable = false)
    private String name;

    private long price;
    private long stock;
    private long reserved;

    protected Product() {
    }

    public Product(String name, long price, long stock) {
        this.name = name;
        this.price = price;
        this.stock = stock;
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

    public long available() {
        return stock - reserved;
    }

    /** 주문 생성: 재고를 잡아 둔다. */
    public void reserve(long qty) {
        reserved += qty;
    }

    /** 결제 대기 주문이 사라짐: 잡아 둔 재고를 푼다. */
    public void release(long qty) {
        reserved -= qty;
    }

    /** 결제 승인: 판매 확정. */
    public void sell(long qty) {
        stock -= qty;
        reserved -= qty;
    }

    /** 환불: 판매된 수량을 되돌린다. */
    public void restock(long qty) {
        stock += qty;
    }
}
