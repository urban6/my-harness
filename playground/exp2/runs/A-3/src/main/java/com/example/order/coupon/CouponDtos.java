package com.example.order.coupon;

public final class CouponDtos {

    private CouponDtos() {
    }

    public record CreateCouponRequest(String code, String type, Long value, Long minOrderAmount,
                                      Long maxDiscountAmount, Integer totalQuantity,
                                      String validFrom, String validUntil) {
    }

    public record CouponResponse(String code, CouponType type, long value, long minOrderAmount,
                                 Long maxDiscountAmount, int totalQuantity, int usedCount,
                                 String validFrom, String validUntil) {

        public static CouponResponse from(Coupon c) {
            return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                    c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getUsedCount(),
                    c.getValidFromText(), c.getValidUntilText());
        }
    }
}
