package com.example.order.coupon;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

public final class CouponDtos {

    private CouponDtos() {
    }

    public record CreateCouponRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "must match [A-Za-z0-9._-]{1,64}") String code,
            @NotNull CouponType type,
            @NotNull @Positive Long value,
            @PositiveOrZero Long minOrderAmount,
            @PositiveOrZero Long maxDiscountAmount,
            @NotNull @Positive Integer totalQuantity,
            @NotNull Instant validFrom,
            @NotNull Instant validUntil) {
    }

    public record CouponResponse(String code, CouponType type, long value, long minOrderAmount,
            Long maxDiscountAmount, int totalQuantity, Instant validFrom, Instant validUntil, int usedCount) {

        static CouponResponse from(Coupon c) {
            return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                    c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getValidFrom(), c.getValidUntil(),
                    c.getUsedCount());
        }
    }
}
