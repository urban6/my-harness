package com.example.order.order;

import com.example.order.common.Times;
import java.time.OffsetDateTime;
import java.util.List;

public record OrderResponse(
        Long id,
        String userId,
        OrderStatus status,
        List<ItemResponse> items,
        String couponCode,
        long subtotal,
        long discount,
        long totalPrice,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime paidAt) {

    public record ItemResponse(Long productId, int quantity, long unitPrice) {
    }

    /** 영속성 컨텍스트(트랜잭션) 안에서 호출해야 items 를 읽을 수 있다. */
    public static OrderResponse from(OrderEntity o) {
        List<ItemResponse> items = o.getItems().stream()
                .map(i -> new ItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(), items, o.getCouponCode(),
                o.getSubtotal(), o.getDiscount(), o.getTotalPrice(),
                Times.utc(o.getCreatedAt()), Times.utc(o.getExpiresAt()), Times.utc(o.getPaidAt()));
    }
}
