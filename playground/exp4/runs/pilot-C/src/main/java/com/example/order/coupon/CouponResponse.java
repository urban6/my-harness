package com.example.order.coupon;

import com.example.order.common.Times;
import java.time.OffsetDateTime;

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
                Times.utc(c.getValidFrom()), Times.utc(c.getValidUntil()));
    }
}
