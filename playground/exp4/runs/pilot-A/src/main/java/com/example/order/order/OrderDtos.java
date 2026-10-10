package com.example.order.order;

import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record OrderItemRequest(Long productId, Integer quantity) {
    }

    public record CreateOrderRequest(List<OrderItemRequest> items, String couponCode) {
    }

    public record PayRequest(String cardToken) {
    }

    public record OrderItemResponse(long productId, int quantity, long unitPrice) {
    }

    public record OrderResponse(Long id, String userId, OrderStatus status, List<OrderItemResponse> items,
                                String couponCode, long subtotal, long discount, long totalPrice,
                                Instant createdAt, Instant expiresAt, Instant paidAt) {
        public static OrderResponse from(PurchaseOrder o) {
            List<OrderItemResponse> items = o.getItems().stream()
                    .map(i -> new OrderItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                    .toList();
            return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(), items, o.getCouponCode(),
                    o.getSubtotal(), o.getDiscount(), o.getTotalPrice(), o.getCreatedAt(), o.getExpiresAt(),
                    o.getPaidAt());
        }
    }

    public record OrderPage(List<OrderResponse> content, String nextCursor) {
    }
}
