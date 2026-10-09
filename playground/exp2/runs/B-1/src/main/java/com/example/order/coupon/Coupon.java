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

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CouponType type;

    @Column(nullable = false)
    private long value;

    @Column(nullable = false)
    private long minOrderAmount;

    /** null 이면 할인 상한 없음. */
    private Long maxDiscountAmount;

    @Column(nullable = false)
    private int totalQuantity;

    /** 이 쿠폰을 사용 중인 주문 수. */
    @Column(nullable = false)
    private int usedCount;

    @Column(nullable = false)
    private Instant validFrom;

    @Column(nullable = false)
    private Instant validUntil;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Coupon() {
    }

    public Coupon(String code, CouponType type, long value, long minOrderAmount, Long maxDiscountAmount,
                  int totalQuantity, Instant validFrom, Instant validUntil, Instant createdAt) {
        this.code = code;
        this.type = type;
        this.value = value;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.totalQuantity = totalQuantity;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.createdAt = createdAt;
    }

    /** R2.4: 정액/정률 → maxDiscountAmount 상한 → subtotal 상한. */
    public long calculateDiscount(long subtotal) {
        long discount = switch (type) {
            case FIXED -> value;
            case RATE -> Math.multiplyExact(subtotal, value) / 100;
        };
        if (maxDiscountAmount != null) {
            discount = Math.min(discount, maxDiscountAmount);
        }
        return Math.min(discount, subtotal);
    }

    public boolean isValidAt(Instant now) {
        return !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    public boolean meetsMinOrderAmount(long subtotal) {
        return subtotal >= minOrderAmount;
    }

    public boolean isExhausted() {
        return usedCount >= totalQuantity;
    }

    public void use() {
        if (isExhausted()) {
            throw new IllegalStateException("coupon is exhausted: " + code);
        }
        usedCount++;
    }

    /** 주문이 사용을 끝내지 못하고 종료되면 사용을 복원한다(R2.6). */
    public void restore() {
        usedCount--;
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
