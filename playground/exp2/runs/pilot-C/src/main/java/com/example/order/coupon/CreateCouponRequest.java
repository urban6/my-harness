package com.example.order.coupon;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
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
        @NotNull @Min(1) Long totalQuantity,
        @NotNull OffsetDateTime validFrom,
        @NotNull OffsetDateTime validUntil) {

    /** Bean Validation 이후의 교차 검증. 위반 시 400. */
    public void validateCross() {
        if (type == CouponType.RATE && value > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "value must be 1..100 for RATE coupon");
        }
        if (!Times.normalize(validFrom).isBefore(Times.normalize(validUntil))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "validFrom must be before validUntil");
        }
    }
}
