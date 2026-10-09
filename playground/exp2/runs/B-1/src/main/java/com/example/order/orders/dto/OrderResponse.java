package com.example.order.orders.dto;

import com.example.order.common.Times;
import com.example.order.orders.Order;
import com.example.order.orders.OrderStatus;
import java.time.OffsetDateTime;
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
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime paidAt
) {

    public static OrderResponse from(Order order) {
        List<OrderItemResponse> items = order.getLines().stream()
                .map(line -> new OrderItemResponse(line.getProductId(), line.getQuantity(), line.getUnitPrice()))
                .toList();
        return new OrderResponse(order.getId(), order.getUserId(), order.getStatus(), items,
                order.getCouponCode(), order.getSubtotal(), order.getDiscount(), order.getTotalPrice(),
                Times.toOffset(order.getCreatedAt()), Times.toOffset(order.getExpiresAt()),
                Times.toOffset(order.getPaidAt()));
    }
}
