package com.example.order.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record ItemRequest(
            @NotNull @Min(1) Long productId,
            @NotNull @Min(1) @Max(100_000) Long quantity) {
    }

    public record CreateOrderRequest(
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid ItemRequest> items,
            @Size(min = 1, max = 50) String couponCode) {

        /** @return true when the same productId appears more than once. */
        public boolean hasDuplicateProduct() {
            Set<Long> seen = new HashSet<>();
            return items.stream().anyMatch(i -> !seen.add(i.productId()));
        }
    }

    public record PayRequest(@NotBlank @Size(max = 200) String cardToken) {
        @Override
        public String toString() {
            return "PayRequest[cardToken=***]";
        }
    }

    public record OrderItemResponse(long productId, int quantity, long unitPrice) {
    }

    public record OrderResponse(Long id, String userId, OrderStatus status, List<OrderItemResponse> items,
            String couponCode, long subtotal, long discount, long totalPrice, Instant createdAt, Instant expiresAt,
            Instant paidAt) {

        public static OrderResponse of(PurchaseOrder o, List<OrderItem> items) {
            return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(),
                    items.stream().map(i -> new OrderItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                            .toList(),
                    o.getCouponCode(), o.getSubtotal(), o.getDiscount(), o.getTotalPrice(), o.getCreatedAt(),
                    o.getExpiresAt(), o.getPaidAt());
        }
    }

    public record OrderPage(List<OrderResponse> content, String nextCursor) {
    }

    /** replayed=true -> served from an existing idempotency key (Idempotent-Replayed header). */
    public record OrderResult(OrderResponse order, boolean replayed) {
    }
}
