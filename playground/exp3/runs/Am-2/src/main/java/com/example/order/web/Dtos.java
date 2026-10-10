package com.example.order.web;

import com.example.order.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record CreateProductRequest(@NotBlank String name, @NotNull @PositiveOrZero Long price,
                                       @NotNull @PositiveOrZero Integer stock) {
    }

    public record ProductResponse(Long id, String name, long price, int stock, int reserved, int available) {
        public static ProductResponse of(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getReserved(), p.getAvailable());
        }
    }

    public record CreateCouponRequest(@NotBlank String code, @NotNull CouponType type, @NotNull @Positive Long value,
                                      @NotNull @PositiveOrZero Long minOrderAmount,
                                      @PositiveOrZero Long maxDiscountAmount,
                                      @NotNull @Positive Integer totalQuantity,
                                      @NotNull Instant validFrom, @NotNull Instant validUntil) {
    }

    public record CouponResponse(String code, CouponType type, long value, long minOrderAmount, Long maxDiscountAmount,
                                 int totalQuantity, Instant validFrom, Instant validUntil, int usedCount) {
        public static CouponResponse of(Coupon c) {
            return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                    c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getValidFrom(), c.getValidUntil(), c.getUsedCount());
        }
    }

    public record OrderItemRequest(@NotNull Long productId, @NotNull @Positive Integer quantity) {
    }

    public record CreateOrderRequest(@NotEmpty List<@Valid @NotNull OrderItemRequest> items, String couponCode) {
    }

    public record PayRequest(@NotBlank String cardToken) {
    }

    public record OrderItemResponse(Long productId, int quantity, long unitPrice) {
    }

    public record OrderResponse(Long id, String userId, OrderStatus status, List<OrderItemResponse> items,
                                String couponCode, long subtotal, long discount, long totalPrice,
                                Instant createdAt, Instant expiresAt, Instant paidAt) {
        public static OrderResponse of(Order o) {
            return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(),
                    o.getItems().stream().map(i -> new OrderItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice())).toList(),
                    o.getCouponCode(), o.getSubtotal(), o.getDiscount(), o.getTotalPrice(),
                    o.getCreatedAt(), o.getExpiresAt(), o.getPaidAt());
        }
    }

    public record OrderPage(List<OrderResponse> content, String nextCursor) {
    }
}
