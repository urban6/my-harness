package com.example.order.order.dto;

import com.example.order.order.OrderItem;

public record OrderItemResponse(Long productId, int quantity, long unitPrice) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.getProductId(), item.getQuantity(), item.getUnitPrice());
    }
}
