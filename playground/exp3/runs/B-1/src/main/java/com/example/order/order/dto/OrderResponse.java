package com.example.order.order.dto;

import java.time.Instant;
import java.util.List;

import com.example.order.order.Order;
import com.example.order.order.OrderStatus;

public record OrderResponse(
        Long id,
        long userId,
        OrderStatus status,
        List<Item> items,
        String couponCode,
        long subtotal,
        long discount,
        long totalPrice,
        Instant createdAt,
        Instant expiresAt,
        Instant paidAt
) {

    public record Item(long productId, int quantity, long unitPrice) {}

    public static OrderResponse from(Order o) {
        List<Item> items = o.getItems().stream()
                .map(i -> new Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(), items, o.getCouponCode(),
                o.getSubtotal(), o.getDiscount(), o.getTotalPrice(), o.getCreatedAt(), o.getExpiresAt(), o.getPaidAt());
    }
}
