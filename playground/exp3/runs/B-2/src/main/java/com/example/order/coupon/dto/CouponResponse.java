package com.example.order.coupon.dto;

import java.time.Instant;

import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponType;

public record CouponResponse(
        String code,
        CouponType type,
        long value,
        Long minOrderAmount,
        Long maxDiscountAmount,
        int totalQuantity,
        Instant validFrom,
        Instant validUntil,
        int usedCount
) {
    public static CouponResponse from(Coupon c) {
        return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getValidFrom(), c.getValidUntil(), c.getUsedCount());
    }
}
