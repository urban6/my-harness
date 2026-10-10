package com.example.order.coupon.dto;

import com.example.order.coupon.CouponType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

public record CreateCouponRequest(
        @NotNull @Pattern(regexp = "^[A-Z0-9]{4,20}$") String code,
        @NotNull CouponType type,
        @NotNull @Min(1) Long value,
        @Min(0) Long minOrderAmount,
        @Min(1) Long maxDiscountAmount,
        @NotNull @Min(1) Integer totalQuantity,
        @NotNull OffsetDateTime validFrom,
        @NotNull OffsetDateTime validUntil
) {

    @JsonIgnore
    @AssertTrue(message = "RATE 쿠폰의 value는 1~100이어야 합니다")
    public boolean isRateValueInRange() {
        return type != CouponType.RATE || value == null || value <= 100;
    }

    @JsonIgnore
    @AssertTrue(message = "validFrom은 validUntil보다 앞서야 합니다")
    public boolean isValidPeriod() {
        return validFrom == null || validUntil == null || validFrom.isBefore(validUntil);
    }
}
