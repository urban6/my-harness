package com.example.order.coupon;

import com.example.order.common.Times;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

public final class CouponDtos {

    private CouponDtos() {
    }

    public record CreateCouponRequest(
            @NotNull @Pattern(regexp = "[A-Z0-9]{4,20}") String code,
            @NotNull CouponType type,
            @NotNull Long value,
            @Min(0) Long minOrderAmount,
            @Min(1) Long maxDiscountAmount,
            @NotNull @Min(1) Integer totalQuantity,
            @NotNull OffsetDateTime validFrom,
            @NotNull OffsetDateTime validUntil) {
    }

    public record CouponResponse(
            String code,
            CouponType type,
            long value,
            long minOrderAmount,
            Long maxDiscountAmount,
            int totalQuantity,
            int usedCount,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil) {

        public static CouponResponse from(Coupon c) {
            return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                    c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getUsedCount(),
                    Times.toOffset(c.getValidFrom()), Times.toOffset(c.getValidUntil()));
        }
    }
}
