package com.example.order.order;

import java.time.OffsetDateTime;
import java.util.List;

/** R3.5 representation, plus the points and refund fields of P2.5. */
public record OrderResponse(long id, String userId, String status, List<ItemResponse> items, String couponCode,
        long subtotal, long discount, long totalPrice, OffsetDateTime createdAt, OffsetDateTime expiresAt,
        OffsetDateTime paidAt, long pointAmount, long cardAmount, long refundedAmount) {

    public record ItemResponse(long productId, long quantity, long unitPrice, long refundedQuantity) {
    }

    public static OrderResponse from(Order o) {
        return new OrderResponse(o.id(), o.userId(), o.status().name(),
                o.items().stream().map(i -> new ItemResponse(i.productId(), i.quantity(), i.unitPrice(),
                        i.refundedQuantity())).toList(),
                o.couponCode(), o.subtotal(), o.discount(), o.totalPrice(), o.createdAt(), o.expiresAt(),
                o.paidAt(), o.pointAmount(), o.cardAmount(), o.refundedAmount());
    }
}
