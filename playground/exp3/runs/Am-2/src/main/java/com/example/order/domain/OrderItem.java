package com.example.order.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;
    private Long productId;
    private int quantity;
    private long unitPrice;

    protected OrderItem() {
    }

    public OrderItem(Order order, Long productId, int quantity, long unitPrice) {
        this.order = order;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public Long getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public long getUnitPrice() { return unitPrice; }
}
