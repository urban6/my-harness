package com.example.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    private String code;
    @Enumerated(EnumType.STRING)
    private CouponType type;
    private long value;
    private long minOrderAmount;
    private Long maxDiscountAmount;
    private int totalQuantity;
    private int usedCount;
    private Instant validFrom;
    private Instant validUntil;

    protected Coupon() {
    }

    public Coupon(String code, CouponType type, long value, long minOrderAmount, Long maxDiscountAmount,
                  int totalQuantity, Instant validFrom, Instant validUntil) {
        this.code = code;
        this.type = type;
        this.value = value;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.totalQuantity = totalQuantity;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    public boolean isValidAt(Instant now) {
        return !now.isBefore(validFrom) && !now.isAfter(validUntil);
    }

    public long discountFor(long subtotal) {
        long discount = type == CouponType.FIXED ? value : subtotal * value / 100;
        if (maxDiscountAmount != null) {
            discount = Math.min(discount, maxDiscountAmount);
        }
        return Math.min(discount, subtotal);
    }

    public String getCode() { return code; }
    public CouponType getType() { return type; }
    public long getValue() { return value; }
    public long getMinOrderAmount() { return minOrderAmount; }
    public Long getMaxDiscountAmount() { return maxDiscountAmount; }
    public int getTotalQuantity() { return totalQuantity; }
    public int getUsedCount() { return usedCount; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
}
