package com.example.order.coupon.dto;

import com.example.order.common.time.Times;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;

public record CreateCouponRequest(
        @NotNull @Pattern(regexp = "^[A-Z0-9]{4,20}$") String code,
        @NotNull @Pattern(regexp = "^(FIXED|RATE)$") String type,
        @NotNull @Min(1) Long value,
        @PositiveOrZero Long minOrderAmount,
        @Min(1) Long maxDiscountAmount,
        @NotNull @Min(1) Long totalQuantity,
        @NotNull OffsetDateTime validFrom,
        @NotNull OffsetDateTime validUntil) {

    @JsonIgnore
    @AssertTrue(message = "RATE 쿠폰의 value는 1~100이어야 합니다")
    public boolean isRateValueInRange() {
        if (!"RATE".equals(type) || value == null) {
            return true;
        }
        return value <= 100;
    }

    @JsonIgnore
    @AssertTrue(message = "validFrom은 validUntil보다 이전이어야 합니다")
    public boolean isValidPeriod() {
        if (validFrom == null || validUntil == null) {
            return true;
        }
        // 저장될 값과 같은 정밀도(마이크로초 절사)로 비교해야 ck_coupons_validity 위반이 500으로 새지 않는다
        return Times.truncate(validFrom.toInstant()).isBefore(Times.truncate(validUntil.toInstant()));
    }
}
