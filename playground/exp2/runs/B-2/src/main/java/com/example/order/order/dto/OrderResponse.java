package com.example.order.order.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.example.order.order.Order;
import com.example.order.order.OrderItem;
import com.example.order.order.OrderStatus;

public record OrderResponse(
        Long id,
        String userId,
        OrderStatus status,
        List<Item> items,
        String couponCode,
        long subtotal,
        long discount,
        long totalPrice,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime paidAt
) {

    public record Item(Long productId, int quantity, long unitPrice) {

        static Item from(OrderItem item) {
            return new Item(item.getProductId(), item.getQuantity(), item.getUnitPrice());
        }
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getItems().stream().map(Item::from).toList(),
                order.getCouponCode(),
                order.getSubtotal(),
                order.getDiscount(),
                order.getTotalPrice(),
                utc(order.getCreatedAt()),
                utc(order.getExpiresAt()),
                utc(order.getPaidAt()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
