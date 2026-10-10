package com.example.order.orders;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(List<ItemRequest> items, String couponCode) {
    }

    public record ItemRequest(Long productId, Integer quantity) {
    }

    public record PayRequest(String cardToken) {
    }

    public record OrderItemResponse(long productId, int quantity, long unitPrice) {
    }

    public record OrderResponse(Long id, String userId, OrderStatus status, List<OrderItemResponse> items,
                                String couponCode, long subtotal, long discount, long totalPrice,
                                OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime paidAt) {

        public static OrderResponse from(Order o) {
            List<OrderItemResponse> items = o.getItems().stream()
                    .map(i -> new OrderItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                    .toList();
            return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(), items, o.getCouponCode(),
                    o.getSubtotal(), o.getDiscount(), o.getTotalPrice(),
                    utc(o.getCreatedAt()), utc(o.getExpiresAt()), utc(o.getPaidAt()));
        }

        private static OffsetDateTime utc(Instant instant) {
            return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
        }
    }

    public record OrderPage(List<OrderResponse> content, String nextCursor) {
    }
}
