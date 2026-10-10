package com.example.order.coupon;

import java.time.Instant;

import com.example.order.common.LenientInstantDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateCouponRequest(
        @NotBlank String code,
        @NotNull CouponType type,
        @NotNull @Positive Long value,
        @PositiveOrZero Long minOrderAmount,
        @Positive Long maxDiscountAmount,
        @NotNull @PositiveOrZero Long totalQuantity,
        @NotNull @JsonDeserialize(using = LenientInstantDeserializer.class) Instant validFrom,
        @NotNull @JsonDeserialize(using = LenientInstantDeserializer.class) Instant validUntil) {
}
