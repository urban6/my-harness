package com.example.order.orders;

import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, OrderStatus status, long totalPrice, List<Item> items, Instant createdAt) {

    public record Item(Long productId, int quantity, long unitPrice) {
    }

    public static OrderResponse from(Order order) {
        List<Item> items = order.getItems().stream()
                .map(item -> new Item(item.getProductId(), item.getQuantity(), item.getUnitPrice()))
                .toList();
        return new OrderResponse(order.getId(), order.getStatus(), order.getTotalPrice(), items, order.getCreatedAt());
    }
}
