package com.example.order.coupon.dto;

import com.example.order.common.web.TimeFormats;
import com.example.order.coupon.Coupon;
import java.time.OffsetDateTime;

public record CouponResponse(
        String code,
        String type,
        long value,
        long minOrderAmount,
        Long maxDiscountAmount,
        long totalQuantity,
        long usedCount,
        OffsetDateTime validFrom,
        OffsetDateTime validUntil) {

    public static CouponResponse from(Coupon c) {
        return new CouponResponse(c.getCode(), c.getType().name(), c.getValue(), c.getMinOrderAmount(),
                c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getUsedCount(),
                TimeFormats.utc(c.getValidFrom()), TimeFormats.utc(c.getValidUntil()));
    }
}
