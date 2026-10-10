package com.example.order.order;

import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String userId,
        OrderStatus status,
        List<Item> items,
        String couponCode,
        long subtotal,
        long discount,
        long totalPrice,
        Instant createdAt,
        Instant expiresAt,
        Instant paidAt) {

    public record Item(long productId, long quantity, long unitPrice) {
    }

    /** 지연 로딩되는 items를 읽으므로 트랜잭션 안에서 호출해야 한다. */
    static OrderResponse from(PurchaseOrder o) {
        return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(),
                o.getItems().stream().map(i -> new Item(i.getProductId(), i.getQuantity(), i.getUnitPrice())).toList(),
                o.getCouponCode(), o.getSubtotal(), o.getDiscount(), o.getTotalPrice(), o.getCreatedAt(),
                o.getExpiresAt(), o.getPaidAt());
    }
}
