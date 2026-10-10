package com.example.order.web;

import com.example.order.domain.Coupon;
import com.example.order.domain.CouponType;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.Product;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record ProductRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull @Min(0) Long price,
            @NotNull @Min(0) @Max(Integer.MAX_VALUE) Integer stock) {
    }

    public record ProductResponse(long id, String name, long price, int stock, int reserved, int available) {
        public static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getReserved(), p.getAvailable());
        }
    }

    public record CouponRequest(
            @NotBlank @Size(max = 100) String code,
            @NotNull CouponType type,
            @NotNull @Min(1) Long value,
            @Min(0) Long minOrderAmount,
            @Min(0) Long maxDiscountAmount,
            @NotNull @Min(1) Integer totalQuantity,
            @NotNull Instant validFrom,
            @NotNull Instant validUntil) {

        @AssertTrue(message = "RATE coupon value must be between 1 and 100")
        public boolean isRateValueValid() {
            return type != CouponType.RATE || value == null || value <= 100;
        }

        @AssertTrue(message = "validUntil must not be before validFrom")
        public boolean isPeriodValid() {
            return validFrom == null || validUntil == null || !validUntil.isBefore(validFrom);
        }
    }

    public record CouponResponse(String code, CouponType type, long value, long minOrderAmount, Long maxDiscountAmount,
                                 int totalQuantity, Instant validFrom, Instant validUntil, int usedCount) {
        public static CouponResponse from(Coupon c) {
            return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                    c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getValidFrom(), c.getValidUntil(), c.getUsedCount());
        }
    }

    public record OrderItemRequest(
            @NotNull Long productId,
            @NotNull @Min(1) @Max(1_000_000) Integer quantity) {
    }

    public record OrderRequest(
            @NotEmpty @Size(max = 100) List<@Valid @NotNull OrderItemRequest> items,
            @Size(max = 100) String couponCode) {
    }

    public record PayRequest(@NotBlank @Size(max = 255) String cardToken) {
    }

    public record OrderItemResponse(long productId, int quantity, long unitPrice) {
    }

    public record OrderResponse(long id, String userId, OrderStatus status, List<OrderItemResponse> items,
                                String couponCode, long subtotal, long discount, long totalPrice,
                                Instant createdAt, Instant expiresAt, Instant paidAt) {
        public static OrderResponse from(Order o) {
            return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(),
                    o.getItems().stream()
                            .map(i -> new OrderItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice())).toList(),
                    o.getCouponCode(), o.getSubtotal(), o.getDiscount(), o.getTotalPrice(),
                    o.getCreatedAt(), o.getExpiresAt(), o.getPaidAt());
        }
    }

    public record OrderPage(List<OrderResponse> content, String nextCursor) {
    }
}
