package com.example.order.order;

import jakarta.persistence.Embeddable;

@Embeddable
public class OrderItem {

    private long productId;
    private int quantity;
    private long unitPrice;

    protected OrderItem() { }

    public OrderItem(long productId, int quantity, long unitPrice) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public long getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public long getUnitPrice() { return unitPrice; }
}
