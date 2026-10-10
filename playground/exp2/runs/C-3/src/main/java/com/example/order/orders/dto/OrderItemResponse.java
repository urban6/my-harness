package com.example.order.orders.dto;

public record OrderItemResponse(Long productId, int quantity, long unitPrice) {
}
