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

    /** 판매되지 않은 보유 수량 */
    @Column(nullable = false)
    private int stock;

    /** 결제 대기 주문이 잡아 둔 수량 */
    @Column(nullable = false)
    private int reserved;

    protected Product() {
    }

    public Product(String name, long price, int stock) {
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    public int available() {
        return stock - reserved;
    }

    public void reserve(int quantity) {
        if (available() < quantity) {
            throw new IllegalStateException("available stock is not enough: productId=" + id);
        }
        reserved += quantity;
    }

    public void releaseReservation(int quantity) {
        reserved -= quantity;
    }

    /** 결제 승인: 예약분을 실제 판매로 확정한다. */
    public void confirmSale(int quantity) {
        stock -= quantity;
        reserved -= quantity;
    }

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
