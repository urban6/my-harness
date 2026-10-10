package com.example.order.coupon.dto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponType;

public record CouponResponse(
        String code,
        CouponType type,
        long value,
        long minOrderAmount,
        Long maxDiscountAmount,
        int totalQuantity,
        int usedCount,
        OffsetDateTime validFrom,
        OffsetDateTime validUntil
) {

    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
                coupon.getCode(),
                coupon.getType(),
                coupon.getValue(),
                coupon.getMinOrderAmount(),
                coupon.getMaxDiscountAmount(),
                coupon.getTotalQuantity(),
                coupon.getUsedCount(),
                coupon.getValidFrom().atOffset(ZoneOffset.UTC),
                coupon.getValidUntil().atOffset(ZoneOffset.UTC));
    }
}
