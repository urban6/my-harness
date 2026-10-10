package com.example.order.coupon;

import java.time.Instant;

import com.example.order.common.error.CouponNotApplicableException;
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

    @Column(nullable = false, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CouponType type;

    @Column(nullable = false, updatable = false)
    private long value;

    @Column(nullable = false, updatable = false)
    private long minOrderAmount;

    @Column(updatable = false)
    private Long maxDiscountAmount;

    @Column(nullable = false, updatable = false)
    private int totalQuantity;

    @Column(nullable = false, insertable = false, updatable = false)
    private int usedCount;

    @Column(nullable = false, updatable = false)
    private Instant validFrom;

    @Column(nullable = false, updatable = false)
    private Instant validUntil;

    protected Coupon() { }

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

    /** 기간·최소 주문 금액·잔여 수량을 확인한다. 수량의 최종 확정은 {@link CouponRepository#use}가 원자적으로 한다. */
    public void assertApplicable(long subtotal, Instant now) {
        if (now.isBefore(validFrom) || now.isAfter(validUntil)) {
            throw new CouponNotApplicableException("사용 기간이 아닌 쿠폰입니다: " + code);
        }
        if (subtotal < minOrderAmount) {
            throw new CouponNotApplicableException("최소 주문 금액(" + minOrderAmount + ")에 미달합니다: " + code);
        }
        if (usedCount >= totalQuantity) {
            throw new CouponNotApplicableException("소진된 쿠폰입니다: " + code);
        }
    }

    public long discountFor(long subtotal) {
        long discount = type == CouponType.FIXED ? value : Math.multiplyExact(subtotal, value) / 100;
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
