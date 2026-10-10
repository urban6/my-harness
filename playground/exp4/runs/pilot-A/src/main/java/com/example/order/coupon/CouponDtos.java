package com.example.order.coupon;

import java.time.Instant;
import java.time.OffsetDateTime;

public final class CouponDtos {

    private CouponDtos() {
    }

    public record CreateCouponRequest(String code, CouponType type, Long value, Long minOrderAmount,
                                      Long maxDiscountAmount, Long totalQuantity,
                                      OffsetDateTime validFrom, OffsetDateTime validUntil) {
    }

    public record CouponResponse(String code, CouponType type, long value, long minOrderAmount,
                                 Long maxDiscountAmount, long totalQuantity, long usedCount,
                                 Instant validFrom, Instant validUntil) {
        static CouponResponse from(Coupon c) {
            return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                    c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getUsedCount(),
                    c.getValidFrom(), c.getValidUntil());
        }
    }
}
