package com.example.order.web.dto;

import com.example.order.domain.Coupon;
import com.example.order.domain.CouponType;
import java.time.Instant;

public record CouponResponse(String code, CouponType type, long value, long minOrderAmount, Long maxDiscountAmount,
                             long totalQuantity, long usedCount, Instant validFrom, Instant validUntil) {

    public static CouponResponse from(Coupon c) {
        return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getUsedCount(), c.getValidFrom(), c.getValidUntil());
    }
}
