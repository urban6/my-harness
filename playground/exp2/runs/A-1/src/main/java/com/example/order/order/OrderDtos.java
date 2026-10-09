package com.example.order.order;

import com.example.order.common.Times;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid OrderItemRequest> items,
            String couponCode) {
    }

    public record OrderItemRequest(
            @NotNull Long productId,
            @NotNull @Min(1) @Max(1000) Integer quantity) {
    }

    public record PayRequest(String cardToken) {
    }

    public record OrderItemResponse(Long productId, int quantity, long unitPrice) {
    }

    public record OrderResponse(
            Long id,
            String userId,
            OrderStatus status,
            List<OrderItemResponse> items,
            String couponCode,
            long subtotal,
            long discount,
            long totalPrice,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            OffsetDateTime paidAt) {

        public static OrderResponse from(Order o) {
            List<OrderItemResponse> items = o.getItems().stream()
                    .map(i -> new OrderItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                    .toList();
            return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(), items, o.getCouponCode(),
                    o.getSubtotal(), o.getDiscount(), o.getTotalPrice(),
                    Times.toOffset(o.getCreatedAt()), Times.toOffset(o.getExpiresAt()), Times.toOffset(o.getPaidAt()));
        }
    }

    public record OrderPage(List<OrderResponse> content, String nextCursor) {
    }
}
