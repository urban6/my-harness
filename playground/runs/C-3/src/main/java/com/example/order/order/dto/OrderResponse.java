package com.example.order.order.dto;

import com.example.order.order.Order;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        long id,
        String status,
        long totalPrice,
        List<OrderItemResponse> items,
        Instant createdAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                order.getTotalPrice(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getCreatedAt());
    }
}
