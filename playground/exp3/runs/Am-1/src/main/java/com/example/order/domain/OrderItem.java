package com.example.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;
    private long productId;
    private int quantity;
    private long unitPrice;

    protected OrderItem() {
    }

    OrderItem(Order order, long productId, int quantity, long unitPrice) {
        this.order = order;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public long getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public long getUnitPrice() { return unitPrice; }
}
