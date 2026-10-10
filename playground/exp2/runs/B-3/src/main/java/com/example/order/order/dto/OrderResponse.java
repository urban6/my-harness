package com.example.order.order.dto;

import com.example.order.order.Order;
import com.example.order.order.OrderStatus;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String userId,
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

    public record Item(Long productId, int quantity, long unitPrice) {
    }

    public static OrderResponse from(Order order) {
        List<Item> items = order.getItems().stream()
                .map(item -> new Item(item.getProductId(), item.getQuantity(), item.getUnitPrice()))
                .toList();
        return new OrderResponse(order.getId(), order.getUserId(), order.getStatus(), items, order.getCouponCode(),
                order.getSubtotal(), order.getDiscount(), order.getTotalPrice(),
                order.getCreatedAt(), order.getExpiresAt(), order.getPaidAt());
    }
}
