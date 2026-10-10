package com.example.order.order;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code pointAmount} is the part of {@code totalPrice} paid with points, the rest ({@link #cardAmount()}) is paid by
 * card. {@code refundedAmount} is the cumulative refund; {@code cardRefundedAmount} the part of it given back to the
 * card (the card is refunded first, P4.6).
 */
public record Order(long id, String userId, OrderStatus status, String couponCode, long subtotal, long discount,
        long totalPrice, long pointAmount, long refundedAmount, long cardRefundedAmount, OffsetDateTime createdAt,
        OffsetDateTime expiresAt, OffsetDateTime paidAt, String paymentId, List<Item> items) {

    public record Item(long productId, long quantity, long unitPrice, long refundedQuantity) {

        public long remainingQuantity() {
            return quantity - refundedQuantity;
        }
    }

    public long cardAmount() {
        return totalPrice - pointAmount;
    }

    public Order withItems(List<Item> newItems) {
        return new Order(id, userId, status, couponCode, subtotal, discount, totalPrice, pointAmount,
                refundedAmount, cardRefundedAmount, createdAt, expiresAt, paidAt, paymentId, newItems);
    }
}
