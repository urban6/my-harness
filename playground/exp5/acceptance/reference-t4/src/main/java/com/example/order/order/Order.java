package com.example.order.order;

import java.time.OffsetDateTime;
import java.util.List;

public record Order(long id, String tenantId, String userId, OrderStatus status, String couponCode, long subtotal,
        long discount, long totalPrice, OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime paidAt,
        String paymentId, List<Item> items) {

    public record Item(long productId, long quantity, long unitPrice) {
    }

    public Order withItems(List<Item> newItems) {
        return new Order(id, tenantId, userId, status, couponCode, subtotal, discount, totalPrice, createdAt, expiresAt,
                paidAt, paymentId, newItems);
    }
}
