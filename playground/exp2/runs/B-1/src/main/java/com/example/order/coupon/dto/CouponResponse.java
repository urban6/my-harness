package com.example.order.coupon.dto;

import com.example.order.common.Times;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponType;
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
        OffsetDateTime validUntil
) {

    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(coupon.getCode(), coupon.getType(), coupon.getValue(),
                coupon.getMinOrderAmount(), coupon.getMaxDiscountAmount(), coupon.getTotalQuantity(),
                coupon.getUsedCount(), Times.toOffset(coupon.getValidFrom()), Times.toOffset(coupon.getValidUntil()));
    }
}
