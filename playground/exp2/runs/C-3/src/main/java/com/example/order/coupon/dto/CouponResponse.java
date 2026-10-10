package com.example.order.coupon.dto;

import com.example.order.coupon.Coupon;
import java.time.Instant;

public record CouponResponse(
        String code,
        String type,
        long value,
        long minOrderAmount,
        Long maxDiscountAmount,
        long totalQuantity,
        long usedCount,
        Instant validFrom,
        Instant validUntil) {

    public static CouponResponse from(Coupon c) {
        return new CouponResponse(c.getCode(), c.getType().name(), c.getValue(), c.getMinOrderAmount(),
                c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getUsedCount(), c.getValidFrom(),
                c.getValidUntil());
    }
}
