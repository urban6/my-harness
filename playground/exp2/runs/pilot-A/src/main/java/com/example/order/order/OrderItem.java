package com.example.order.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class OrderItem {

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    protected OrderItem() {
    }

    public OrderItem(long productId, int quantity, long unitPrice) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPrice() {
        return unitPrice;
    }

    public long lineTotal() {
        return Math.multiplyExact(unitPrice, (long) quantity);
    }
}
