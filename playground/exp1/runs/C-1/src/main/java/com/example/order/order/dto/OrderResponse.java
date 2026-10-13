package com.example.order.order.dto;

import java.time.Instant;
import java.util.List;

import com.example.order.order.Order;
import com.example.order.order.OrderStatus;

public record OrderResponse(
        Long id,
        OrderStatus status,
        long totalPrice,
        List<OrderItemResponse> items,
        Instant createdAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getTotalPrice(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getCreatedAt());
    }
}
