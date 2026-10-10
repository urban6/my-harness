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

    @Column(nullable = false, length = 20, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CouponType type;

    @Column(nullable = false)
    private long value;

    @Column(name = "min_order_amount", nullable = false)
    private long minOrderAmount;

    /** null 이면 할인 상한 없음 */
    @Column(name = "max_discount_amount")
    private Long maxDiscountAmount;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    /** 이 쿠폰을 사용 중인 주문 수 */
    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    /** 요청에 담긴 원래 표현(오프셋 포함)을 그대로 돌려주기 위해 보관한다. */
    @Column(name = "valid_from_text", nullable = false, length = 100)
    private String validFromText;

    @Column(name = "valid_until_text", nullable = false, length = 100)
    private String validUntilText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Coupon() {
    }

    public Coupon(String code, CouponType type, long value, long minOrderAmount, Long maxDiscountAmount,
                  int totalQuantity, Instant validFrom, String validFromText,
                  Instant validUntil, String validUntilText, Instant createdAt) {
        this.code = code;
        this.type = type;
        this.value = value;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.totalQuantity = totalQuantity;
        this.usedCount = 0;
        this.validFrom = validFrom;
        this.validFromText = validFromText;
        this.validUntil = validUntil;
        this.validUntilText = validUntilText;
        this.createdAt = createdAt;
    }

    /** R2.4: 정액/정률 → maxDiscountAmount 상한 → subtotal 상한. */
    public long discountFor(long subtotal) {
        long discount = type == CouponType.FIXED ? value : Math.floorDiv(Math.multiplyExact(subtotal, value), 100L);
        if (maxDiscountAmount != null) {
            discount = Math.min(discount, maxDiscountAmount);
        }
        return Math.min(discount, subtotal);
    }

    public boolean isValidAt(Instant at) {
        return !at.isBefore(validFrom) && at.isBefore(validUntil);
    }

    public boolean isExhausted() {
        return usedCount >= totalQuantity;
    }

    public void use() {
        if (isExhausted()) {
            throw new IllegalStateException("coupon " + code + " exhausted");
        }
        usedCount++;
    }

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

    public String getValidFromText() {
        return validFromText;
    }

    public String getValidUntilText() {
        return validUntilText;
    }
}
