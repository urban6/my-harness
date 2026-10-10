package com.example.order.order;

import java.time.OffsetDateTime;
import java.util.List;

public record Order(long id, String userId, OrderStatus status, String couponCode, long subtotal, long discount,
        long totalPrice, long pointAmount, long refundedAmount, long cardRefundedAmount, OffsetDateTime createdAt,
        OffsetDateTime expiresAt, OffsetDateTime paidAt, String paymentId, List<Item> items) {

    public record Item(long productId, long quantity, long unitPrice, long refundedQuantity) {

        public long remaining() {
            return quantity - refundedQuantity;
        }
    }

    /** P2.5: the part of totalPrice paid by card. */
    public long cardAmount() {
        return totalPrice - pointAmount;
    }

    public Order withItems(List<Item> newItems) {
        return new Order(id, userId, status, couponCode, subtotal, discount, totalPrice, pointAmount, refundedAmount,
                cardRefundedAmount, createdAt, expiresAt, paidAt, paymentId, newItems);
    }
}
