package com.example.order.order;

import com.example.order.common.Times;

import java.time.OffsetDateTime;
import java.util.List;

public record OrderResponse(long id, String userId, OrderStatus status, List<Item> items, String couponCode,
                            long subtotal, long discount, long totalPrice, OffsetDateTime createdAt,
                            OffsetDateTime expiresAt, OffsetDateTime paidAt) {

    public record Item(long productId, int quantity, long unitPrice) {
    }

    public static OrderResponse of(Order o) {
        List<Item> items = o.getItems().stream()
                .map(l -> new Item(l.getProductId(), l.getQuantity(), l.getUnitPrice()))
                .toList();
        return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(), items, o.getCouponCode(),
                o.getSubtotal(), o.getDiscount(), o.getTotalPrice(), Times.toOffset(o.getCreatedAt()),
                Times.toOffset(o.getExpiresAt()), Times.toOffset(o.getPaidAt()));
    }
}
