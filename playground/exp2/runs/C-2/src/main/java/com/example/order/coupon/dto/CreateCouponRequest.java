package com.example.order.coupon.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 시각은 String으로 받아 서비스에서 오프셋 포함 ISO-8601로 파싱한다 (필드 단위 오류 메시지). */
public record CreateCouponRequest(
        @NotNull @Pattern(regexp = "^[A-Z0-9]{4,20}$") String code,
        @NotNull @Pattern(regexp = "^(FIXED|RATE)$") String type,
        @NotNull @Min(1) Long value,
        @Min(0) Long minOrderAmount,
        @Min(1) Long maxDiscountAmount,
        @NotNull @Min(1) Long totalQuantity,
        @NotNull String validFrom,
        @NotNull String validUntil) {
}
