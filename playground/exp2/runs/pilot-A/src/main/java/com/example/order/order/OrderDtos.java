package com.example.order.order;

import com.example.order.common.Times;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid OrderItemRequest> items,
            String couponCode) {

        @AssertTrue(message = "items must not contain the same productId twice")
        public boolean isProductIdsUnique() {
            if (items == null) {
                return true;
            }
            List<Long> ids = items.stream().filter(Objects::nonNull).map(OrderItemRequest::productId)
                    .filter(Objects::nonNull).toList();
            return new HashSet<>(ids).size() == ids.size();
        }

        @AssertTrue(message = "couponCode must not be blank")
        public boolean isCouponCodeNotBlank() {
            return couponCode == null || !couponCode.isBlank();
        }
    }

    public record OrderItemRequest(
            @NotNull Long productId,
            @NotNull @Min(1) @Max(1000) Integer quantity) {
    }

    public record PayRequest(@NotBlank String cardToken) {
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
                    o.getSubtotal(), o.getDiscount(), o.getTotalPrice(), Times.utc(o.getCreatedAt()),
                    Times.utc(o.getExpiresAt()), Times.utc(o.getPaidAt()));
        }
    }

    public record OrderPage(List<OrderResponse> content, String nextCursor) {
    }
}
