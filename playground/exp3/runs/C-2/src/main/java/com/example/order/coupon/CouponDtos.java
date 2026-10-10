package com.example.order.coupon;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class CouponDtos {

    private CouponDtos() {
    }

    public record CreateCouponRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String code,
            @NotNull CouponType type,
            @NotNull @Min(1) Long value,
            @Min(0) Long minOrderAmount,
            @Min(1) Long maxDiscountAmount,
            @NotNull @Min(1) @Max(1_000_000_000L) Long totalQuantity,
            @NotNull Instant validFrom,
            @NotNull Instant validUntil) {
    }

    public record CouponResponse(String code, CouponType type, long value, long minOrderAmount,
            Long maxDiscountAmount, int totalQuantity, Instant validFrom, Instant validUntil, int usedCount) {

        public static CouponResponse from(Coupon c) {
            return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                    c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getValidFrom(), c.getValidUntil(),
                    c.getUsedCount());
        }
    }
}
