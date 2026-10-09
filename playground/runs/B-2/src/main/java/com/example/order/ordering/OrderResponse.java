package com.example.order.ordering;

import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        OrderStatus status,
        long totalPrice,
        List<ItemResponse> items,
        Instant createdAt
) {

    public record ItemResponse(Long productId, int quantity, long unitPrice) {
    }

    static OrderResponse from(Order order) {
        List<ItemResponse> items = order.getItems().stream()
                .map(i -> new ItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return new OrderResponse(order.getId(), order.getStatus(), order.getTotalPrice(), items,
                order.getCreatedAt());
    }
}
