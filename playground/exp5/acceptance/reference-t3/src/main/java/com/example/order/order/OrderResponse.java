package com.example.order.order;

import java.time.OffsetDateTime;
import java.util.List;

/** R3.5 + P2.5 representation. */
public record OrderResponse(long id, String userId, String status, List<ItemResponse> items, String couponCode,
        long subtotal, long discount, long totalPrice, long pointAmount, long cardAmount, long refundedAmount,
        OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime paidAt) {

    public record ItemResponse(long productId, long quantity, long unitPrice, long refundedQuantity) {
    }

    public static OrderResponse from(Order o) {
        return new OrderResponse(o.id(), o.userId(), o.status().name(),
                o.items().stream().map(i -> new ItemResponse(i.productId(), i.quantity(), i.unitPrice(),
                        i.refundedQuantity())).toList(),
                o.couponCode(), o.subtotal(), o.discount(), o.totalPrice(), o.pointAmount(), o.cardAmount(),
                o.refundedAmount(), o.createdAt(), o.expiresAt(), o.paidAt());
    }
}
