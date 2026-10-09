package com.example.order.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class OrderLine {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    /** 주문 시점의 상품 가격. */
    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    protected OrderLine() {
    }

    public OrderLine(Long productId, int quantity, long unitPrice) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public long amount() {
        return Math.multiplyExact(unitPrice, (long) quantity);
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPrice() {
        return unitPrice;
    }
}
