package com.example.order.coupon.dto;

import java.time.Instant;

import com.example.order.coupon.CouponType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateCouponRequest(
        @NotBlank @Size(max = 100) String code,
        @NotNull CouponType type,
        @NotNull @Positive Long value,
        @NotNull @PositiveOrZero Long minOrderAmount,
        @Positive Long maxDiscountAmount,
        @NotNull @Positive Integer totalQuantity,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil
) {

    @AssertTrue(message = "RATE 쿠폰의 value는 1~100 이어야 합니다")
    boolean isValueInRangeForType() {
        return type != CouponType.RATE || value == null || value <= 100;
    }

    @AssertTrue(message = "validUntil은 validFrom 이후여야 합니다")
    boolean isPeriodOrdered() {
        return validFrom == null || validUntil == null || validUntil.isAfter(validFrom);
    }
}
