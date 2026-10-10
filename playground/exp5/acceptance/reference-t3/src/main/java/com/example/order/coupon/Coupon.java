package com.example.order.coupon;

import java.time.OffsetDateTime;

public record Coupon(String code, String type, long value, long minOrderAmount, Long maxDiscountAmount,
        long totalQuantity, long usedCount, OffsetDateTime validFrom, OffsetDateTime validUntil) {

    /** R2.4: FIXED = value, RATE = floor(subtotal * value / 100); cap by maxDiscountAmount, then by subtotal. */
    public long discountFor(long subtotal) {
        long discount = "FIXED".equals(type) ? value : Math.floorDiv(Math.multiplyExact(subtotal, value), 100L);
        if (maxDiscountAmount != null) {
            discount = Math.min(discount, maxDiscountAmount);
        }
        return Math.min(discount, subtotal);
    }

    /** validFrom <= now < validUntil */
    public boolean isValidAt(OffsetDateTime now) {
        return !now.isBefore(validFrom) && now.isBefore(validUntil);
    }
}
