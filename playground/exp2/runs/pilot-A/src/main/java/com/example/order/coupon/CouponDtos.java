package com.example.order.coupon;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

public final class CouponDtos {

    private CouponDtos() {
    }

    public record CreateCouponRequest(
            @NotNull @Pattern(regexp = "^[A-Z0-9]{4,20}$") String code,
            @NotNull CouponType type,
            @NotNull @Min(1) Long value,
            @Min(0) Long minOrderAmount,
            @Min(1) Long maxDiscountAmount,
            @NotNull @Min(1) Long totalQuantity,
            @NotNull OffsetDateTime validFrom,
            @NotNull OffsetDateTime validUntil) {

        @AssertTrue(message = "RATE coupon value must be between 1 and 100")
        public boolean isRateValueInRange() {
            return type != CouponType.RATE || value == null || value <= 100;
        }

        @AssertTrue(message = "validFrom must be before validUntil")
        public boolean isValidPeriod() {
            return validFrom == null || validUntil == null || validFrom.isBefore(validUntil);
        }
    }

    public record CouponResponse(String code, CouponType type, long value, long minOrderAmount,
                                 Long maxDiscountAmount, long totalQuantity, long usedCount,
                                 OffsetDateTime validFrom, OffsetDateTime validUntil) {

        public static CouponResponse from(Coupon c) {
            return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                    c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getUsedCount(),
                    c.getValidFrom(), c.getValidUntil());
        }
    }
}
