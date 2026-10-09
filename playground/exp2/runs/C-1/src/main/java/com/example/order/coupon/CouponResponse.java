package com.example.order.coupon;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record CouponResponse(
        String code,
        CouponType type,
        long value,
        long minOrderAmount,
        Long maxDiscountAmount,
        int totalQuantity,
        int usedCount,
        OffsetDateTime validFrom,
        OffsetDateTime validUntil) {

    public static CouponResponse from(Coupon c) {
        return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getUsedCount(),
                c.getValidFrom().atOffset(ZoneOffset.UTC), c.getValidUntil().atOffset(ZoneOffset.UTC));
    }
}
