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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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

    @Column(name = "min_order_amount", nullable = false)
    private long minOrderAmount;

    @Column(name = "max_discount_amount")
    private Long maxDiscountAmount;

    @Column(name = "total_quantity", nullable = false)
    private long totalQuantity;

    @Column(name = "used_count", nullable = false)
    private long usedCount;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    // 요청에 담겨 온 오프셋을 그대로 돌려주기 위해 따로 보관한다 (timestamptz는 오프셋을 잃는다).
    @Column(name = "valid_from_offset", nullable = false)
    private int validFromOffset;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "valid_until_offset", nullable = false)
    private int validUntilOffset;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Coupon() {
    }

    public Coupon(String code, CouponType type, long value, long minOrderAmount, Long maxDiscountAmount,
                  long totalQuantity, OffsetDateTime validFrom, OffsetDateTime validUntil, Instant createdAt) {
        this.code = code;
        this.type = type;
        this.value = value;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.totalQuantity = totalQuantity;
        this.usedCount = 0;
        this.validFrom = validFrom.toInstant();
        this.validFromOffset = validFrom.getOffset().getTotalSeconds();
        this.validUntil = validUntil.toInstant();
        this.validUntilOffset = validUntil.getOffset().getTotalSeconds();
        this.createdAt = createdAt;
    }

    /** R2.4: 정액/정률 → maxDiscountAmount 상한 → subtotal 상한. */
    public long discountFor(long subtotal) {
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

    public boolean isExhausted() {
        return usedCount >= totalQuantity;
    }

    public void use() {
        if (isExhausted()) {
            throw new IllegalStateException("coupon " + code + " exhausted");
        }
        usedCount++;
    }

    public void release() {
        if (usedCount > 0) {
            usedCount--;
        }
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

    public OffsetDateTime getValidFrom() {
        return validFrom.atOffset(ZoneOffset.ofTotalSeconds(validFromOffset));
    }

    public OffsetDateTime getValidUntil() {
        return validUntil.atOffset(ZoneOffset.ofTotalSeconds(validUntilOffset));
    }
}
