package com.example.order.order;

public record OrderItemRow(long orderId, int lineNo, long productId, int quantity, long unitPrice) {
}
