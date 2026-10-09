package com.example.order.coupon;

import com.example.order.common.Times;
import java.time.OffsetDateTime;

public record CouponResponse(String code, CouponType type, long value, long minOrderAmount, Long maxDiscountAmount,
                             long totalQuantity, long usedCount, OffsetDateTime validFrom, OffsetDateTime validUntil) {
    public static CouponResponse from(Coupon c) {
        return new CouponResponse(c.getCode(), c.getDiscountType(), c.getDiscountValue(), c.getMinOrderAmount(),
                c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getUsedCount(),
                Times.normalize(c.getValidFrom()), Times.normalize(c.getValidUntil()));
    }
}
