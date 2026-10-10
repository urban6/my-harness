package com.example.order.coupon.dto;

import java.time.Instant;

import com.example.order.coupon.CouponType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateCouponRequest(
        @NotBlank @Size(max = 64) String code,
        @NotNull CouponType type,
        @NotNull @Positive Long value,
        @PositiveOrZero Long minOrderAmount,
        @Positive Long maxDiscountAmount,
        @NotNull @Positive Integer totalQuantity,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil
) {}
