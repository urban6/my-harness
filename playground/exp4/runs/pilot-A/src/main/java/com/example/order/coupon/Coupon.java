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

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CouponType type;

    @Column(name = "value")
    private long value;

    private long minOrderAmount;
    private Long maxDiscountAmount;
    private long totalQuantity;
    private long usedCount;
    private Instant validFrom;
    private Instant validUntil;

    protected Coupon() {
    }

    public Coupon(String code, CouponType type, long value, long minOrderAmount, Long maxDiscountAmount,
                  long totalQuantity, Instant validFrom, Instant validUntil) {
        this.code = code;
        this.type = type;
        this.value = value;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.totalQuantity = totalQuantity;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
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

    public long getTotalQuantity() {
        return totalQuantity;
    }

    public long getUsedCount() {
        return usedCount;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public boolean isValidAt(Instant now) {
        return !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    public boolean isExhausted() {
        return usedCount >= totalQuantity;
    }

    /** 할인액: 정액/정률 계산 후 maxDiscountAmount, 마지막으로 subtotal로 상한. */
    public long discountFor(long subtotal) {
        long discount = type == CouponType.FIXED ? value : Math.floorDiv(subtotal * value, 100);
        if (maxDiscountAmount != null) {
            discount = Math.min(discount, maxDiscountAmount);
        }
        return Math.min(discount, subtotal);
    }

    public void use() {
        usedCount++;
    }

    public void restore() {
        usedCount--;
    }
}
