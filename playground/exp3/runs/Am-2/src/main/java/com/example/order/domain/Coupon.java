package com.example.order.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
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

    /** Discount for the given subtotal; never exceeds the subtotal. */
    public long discountFor(long subtotal) {
        long discount = type == CouponType.FIXED ? value : subtotal * value / 100;
        if (maxDiscountAmount != null) {
            discount = Math.min(discount, maxDiscountAmount);
        }
        return Math.min(discount, subtotal);
    }

    public Long getId() { return id; }
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
