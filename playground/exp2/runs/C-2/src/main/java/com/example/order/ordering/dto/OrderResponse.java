package com.example.order.ordering.dto;

import com.example.order.common.web.TimeFormats;
import com.example.order.ordering.Order;
import java.time.OffsetDateTime;
import java.util.List;

public record OrderResponse(
        long id,
        String userId,
        String status,
        List<Item> items,
        String couponCode,
        long subtotal,
        long discount,
        long totalPrice,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime paidAt) {

    public record Item(long productId, int quantity, long unitPrice) {
    }

    /** 트랜잭션 안에서 호출해야 한다 (items는 LAZY). */
    public static OrderResponse from(Order o) {
        List<Item> items = o.getItems().stream()
                .map(l -> new Item(l.productId(), l.quantity(), l.unitPrice()))
                .toList();
        return new OrderResponse(o.getId(), o.getUserId(), o.getStatus().name(), items, o.getCouponCode(),
                o.getSubtotal(), o.getDiscount(), o.getTotalPrice(),
                TimeFormats.utc(o.getCreatedAt()), TimeFormats.utc(o.getExpiresAt()), TimeFormats.utc(o.getPaidAt()));
    }
}
