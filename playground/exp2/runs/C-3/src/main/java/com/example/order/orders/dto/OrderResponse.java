package com.example.order.orders.dto;

import com.example.order.orders.Order;
import com.example.order.orders.OrderStatus;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String userId,
        OrderStatus status,
        List<OrderItemResponse> items,
        String couponCode,
        long subtotal,
        long discount,
        long totalPrice,
        Instant createdAt,
        Instant expiresAt,
        Instant paidAt) {

    public static OrderResponse from(Order o) {
        List<OrderItemResponse> items = o.getItems().stream()
                .map(i -> new OrderItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(), items, o.getCouponCode(),
                o.getSubtotal(), o.getDiscount(), o.getTotalPrice(), o.getCreatedAt(), o.getExpiresAt(),
                o.getPaidAt());
    }
}
