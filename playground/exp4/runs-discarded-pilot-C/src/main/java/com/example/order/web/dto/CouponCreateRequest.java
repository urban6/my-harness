package com.example.order.web.dto;

import com.example.order.domain.CouponType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** validFrom/validUntil 은 String 으로 받아 서비스에서 OffsetDateTime.parse 로 직접 파싱한다 (오프셋 필수). */
public record CouponCreateRequest(
        @NotNull @Pattern(regexp = "[A-Z0-9]{4,20}") String code,
        @NotNull CouponType type,
        @NotNull @Min(1) Long value,
        @Min(0) Long minOrderAmount,
        @Min(1) Long maxDiscountAmount,
        @NotNull @Min(1) Long totalQuantity,
        @NotNull String validFrom,
        @NotNull String validUntil) {
}
