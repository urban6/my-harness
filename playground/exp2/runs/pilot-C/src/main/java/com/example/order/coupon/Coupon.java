package com.example.order.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "coupons")
public class Coupon {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "code", nullable = false)
    private String code;
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private CouponType discountType;
    @Column(name = "discount_value", nullable = false)
    private long discountValue;
    private long minOrderAmount;
    private Long maxDiscountAmount;
    private long totalQuantity;
    private long usedCount;
    private OffsetDateTime validFrom;
    private OffsetDateTime validUntil;
    private OffsetDateTime createdAt;

    protected Coupon() {
    }

    public Coupon(String code, CouponType type, long value, long minOrderAmount, Long maxDiscountAmount,
                  long totalQuantity, OffsetDateTime validFrom, OffsetDateTime validUntil, OffsetDateTime createdAt) {
        this.code = code;
        this.discountType = type;
        this.discountValue = value;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.totalQuantity = totalQuantity;
        this.usedCount = 0;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public CouponType getDiscountType() { return discountType; }
    public long getDiscountValue() { return discountValue; }
    public long getMinOrderAmount() { return minOrderAmount; }
    public Long getMaxDiscountAmount() { return maxDiscountAmount; }
    public long getTotalQuantity() { return totalQuantity; }
    public long getUsedCount() { return usedCount; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public OffsetDateTime getValidUntil() { return validUntil; }

    public void use() { this.usedCount += 1; }
    public void restore() { this.usedCount -= 1; }

    /** R2.4 할인액: 정액/정률 -> maxDiscountAmount 상한 -> subtotal 상한. */
    public long discountFor(long subtotal) {
        long d = discountType == CouponType.FIXED ? discountValue : Math.multiplyExact(subtotal, discountValue) / 100;
        if (maxDiscountAmount != null) {
            d = Math.min(d, maxDiscountAmount);
        }
        return Math.min(d, subtotal);
    }
}
