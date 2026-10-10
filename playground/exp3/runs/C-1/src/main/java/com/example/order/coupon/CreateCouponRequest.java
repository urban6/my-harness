package com.example.order.coupon;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record CreateCouponRequest(
        @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "must match ^[A-Za-z0-9_-]{1,64}$") String code,
        @NotNull CouponType type,
        @NotNull @Min(1) @Max(1_000_000_000L) Long value,
        @PositiveOrZero Long minOrderAmount,
        @PositiveOrZero Long maxDiscountAmount,
        @NotNull @Min(1) @Max(1_000_000_000L) Integer totalQuantity,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil) {

    public CreateCouponRequest {
        // PostgreSQL keeps microseconds: truncate so POST and GET agree and the period check uses stored values
        validFrom = validFrom == null ? null : validFrom.truncatedTo(ChronoUnit.MICROS);
        validUntil = validUntil == null ? null : validUntil.truncatedTo(ChronoUnit.MICROS);
    }
}
