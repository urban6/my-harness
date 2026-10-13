package com.example.order.orders.dto;

import com.example.order.orders.OrderItem;

public record OrderItemResponse(
        Long productId,
        int quantity,
        long unitPrice
) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.getProductId(), item.getQuantity(), item.getUnitPrice());
    }
}
