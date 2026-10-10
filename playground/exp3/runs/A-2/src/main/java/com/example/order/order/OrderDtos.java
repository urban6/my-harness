package com.example.order.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotEmpty List<@NotNull @Valid ItemRequest> items,
            @Size(max = 64) String couponCode) {
    }

    public record ItemRequest(@NotNull Long productId, @NotNull @Positive Integer quantity) {
    }

    public record PayRequest(@NotBlank @Size(max = 255) String cardToken) {
    }

    public record ItemResponse(long productId, int quantity, long unitPrice) {
    }

    public record OrderResponse(Long id, String userId, OrderStatus status, List<ItemResponse> items,
            String couponCode, long subtotal, long discount, long totalPrice, Instant createdAt,
            Instant expiresAt, Instant paidAt) {

        static OrderResponse from(Order o) {
            return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(),
                    o.getItems().stream().map(i -> new ItemResponse(i.productId(), i.quantity(), i.unitPrice()))
                            .toList(),
                    o.getCouponCode(), o.getSubtotal(), o.getDiscount(), o.getTotalPrice(), o.getCreatedAt(),
                    o.getExpiresAt(), o.getPaidAt());
        }
    }

    public record OrderPage(List<OrderResponse> content, String nextCursor) {
    }
}
