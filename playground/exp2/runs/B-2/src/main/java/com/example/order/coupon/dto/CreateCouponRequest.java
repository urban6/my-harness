package com.example.order.coupon.dto;

import java.time.OffsetDateTime;

import com.example.order.coupon.CouponType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateCouponRequest(
        @NotNull @Pattern(regexp = "^[A-Z0-9]{4,20}$") String code,
        @NotNull CouponType type,
        @NotNull Long value,
        @Min(0) Long minOrderAmount,
        @Min(1) Long maxDiscountAmount,
        @NotNull @Min(1) Integer totalQuantity,
        @NotNull OffsetDateTime validFrom,
        @NotNull OffsetDateTime validUntil
) {

    @JsonIgnore
    @AssertTrue(message = "value는 FIXED면 1 이상, RATE면 1~100이어야 합니다")
    public boolean isValueInRange() {
        if (type == null || value == null) {
            return true;
        }
        return switch (type) {
            case FIXED -> value >= 1;
            case RATE -> value >= 1 && value <= 100;
        };
    }

    @JsonIgnore
    @AssertTrue(message = "validFrom은 validUntil보다 앞서야 합니다")
    public boolean isValidPeriod() {
        return validFrom == null || validUntil == null || validFrom.isBefore(validUntil);
    }
}
