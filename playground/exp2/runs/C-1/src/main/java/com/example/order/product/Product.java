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

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private int reserved;

    protected Product() {
    }

    public static Product create(String name, long price, int stock) {
        Product p = new Product();
        p.name = name;
        p.price = price;
        p.stock = stock;
        p.reserved = 0;
        return p;
    }

    public int available() {
        return stock - reserved;
    }

    /** 주문 생성: 예약 수량 증가. */
    public void reserve(int quantity) {
        reserved += quantity;
    }

    /** 예약 복원(취소·만료·결제 거절). */
    public void release(int quantity) {
        reserved -= quantity;
    }

    /** 결제 승인: 재고와 예약을 함께 차감. */
    public void commitSale(int quantity) {
        stock -= quantity;
        reserved -= quantity;
    }

    /** 환불: 재고 복원. */
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
