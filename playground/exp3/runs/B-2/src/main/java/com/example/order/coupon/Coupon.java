package com.example.order.coupon;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    @Column(nullable = false)
    private long value;

    private Long minOrderAmount;

    private Long maxDiscountAmount;

    @Column(nullable = false)
    private int totalQuantity;

    @Column(nullable = false)
    private int usedCount;

    @Column(nullable = false)
    private Instant validFrom;

    @Column(nullable = false)
    private Instant validUntil;

    protected Coupon() { }

    public Coupon(String code, CouponType type, long value, Long minOrderAmount, Long maxDiscountAmount,
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

    public boolean isSatisfiedBy(long subtotal) {
        return minOrderAmount == null || subtotal >= minOrderAmount;
    }

    /** FIXED는 정액, RATE는 정률(원 단위 내림). 최대 할인액과 주문 금액을 넘지 않는다. */
    public long discountFor(long subtotal) {
        long raw = type == CouponType.FIXED ? value : subtotal * value / 100;
        if (maxDiscountAmount != null) {
            raw = Math.min(raw, maxDiscountAmount);
        }
        return Math.min(raw, subtotal);
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public CouponType getType() { return type; }
    public long getValue() { return value; }
    public Long getMinOrderAmount() { return minOrderAmount; }
    public Long getMaxDiscountAmount() { return maxDiscountAmount; }
    public int getTotalQuantity() { return totalQuantity; }
    public int getUsedCount() { return usedCount; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
}
