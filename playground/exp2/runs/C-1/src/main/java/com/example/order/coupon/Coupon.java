package com.example.order.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 10)
    private CouponType type;

    @Column(name = "discount_value", nullable = false)
    private long value;

    @Column(name = "min_order_amount", nullable = false)
    private long minOrderAmount;

    @Column(name = "max_discount_amount")
    private Long maxDiscountAmount;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    protected Coupon() {
    }

    public static Coupon create(String code, CouponType type, long value, long minOrderAmount,
                                Long maxDiscountAmount, int totalQuantity, Instant validFrom, Instant validUntil) {
        Coupon c = new Coupon();
        c.code = code;
        c.type = type;
        c.value = value;
        c.minOrderAmount = minOrderAmount;
        c.maxDiscountAmount = maxDiscountAmount;
        c.totalQuantity = totalQuantity;
        c.usedCount = 0;
        c.validFrom = validFrom;
        c.validUntil = validUntil;
        return c;
    }

    public boolean isWithinPeriod(Instant now) {
        return !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    public boolean isExhausted() {
        return usedCount >= totalQuantity;
    }

    public void use() {
        usedCount++;
    }

    public void restore() {
        usedCount--;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public CouponType getType() {
        return type;
    }

    public long getValue() {
        return value;
    }

    public long getMinOrderAmount() {
        return minOrderAmount;
    }

    public Long getMaxDiscountAmount() {
        return maxDiscountAmount;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }
}
