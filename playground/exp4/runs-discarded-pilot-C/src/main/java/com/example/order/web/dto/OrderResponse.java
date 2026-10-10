package com.example.order.web.dto;

import com.example.order.domain.Order;
import com.example.order.domain.OrderItem;
import com.example.order.domain.OrderStatus;
import java.time.Instant;
import java.util.List;

public record OrderResponse(long id, String userId, OrderStatus status, List<Item> items, String couponCode,
                            long subtotal, long discount, long totalPrice,
                            Instant createdAt, Instant expiresAt, Instant paidAt) {

    public record Item(long productId, long quantity, long unitPrice) {
    }

    public static OrderResponse from(Order o) {
        List<Item> items = o.getItems().stream()
                .map((OrderItem i) -> new Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(), items, o.getCouponCode(),
                o.getSubtotal(), o.getDiscount(), o.getTotalPrice(), o.getCreatedAt(), o.getExpiresAt(), o.getPaidAt());
    }
}
