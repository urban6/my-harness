package com.example.order.coupon;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

public record CouponCreateRequest(
        @NotNull @Pattern(regexp = "^[A-Z0-9]{4,20}$") String code,
        @NotNull CouponType type,
        @NotNull @Min(1) Long value,
        @Min(0) Long minOrderAmount,
        @Min(1) Long maxDiscountAmount,
        @NotNull @Min(1) Integer totalQuantity,
        @NotNull OffsetDateTime validFrom,
        @NotNull OffsetDateTime validUntil) {

    @JsonIgnore
    @AssertTrue
    public boolean isValueInRange() {
        return type != CouponType.RATE || value == null || value <= 100;
    }

    @JsonIgnore
    @AssertTrue
    public boolean isPeriodValid() {
        if (validFrom == null || validUntil == null) {
            return true;
        }
        return validFrom.toInstant().truncatedTo(ChronoUnit.MICROS)
                .isBefore(validUntil.toInstant().truncatedTo(ChronoUnit.MICROS));
    }
}
