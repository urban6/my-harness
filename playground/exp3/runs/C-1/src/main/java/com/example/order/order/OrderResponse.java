package com.example.order.order;

import java.time.Instant;
import java.util.List;

public record OrderResponse(
        long id,
        String userId,
        OrderStatus status,
        List<Item> items,
        String couponCode,
        long subtotal,
        long discount,
        long totalPrice,
        Instant createdAt,
        Instant expiresAt,
        Instant paidAt) {

    public record Item(long productId, int quantity, long unitPrice) {
    }

    public static OrderResponse from(OrderRow o, List<OrderItemRow> items) {
        return new OrderResponse(o.id(), o.userId(), o.status(),
                items.stream().map(i -> new Item(i.productId(), i.quantity(), i.unitPrice())).toList(),
                o.couponCode(), o.subtotal(), o.discount(), o.totalPrice(), o.createdAt(), o.expiresAt(),
                o.paidAt());
    }
}
