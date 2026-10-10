package com.example.order.coupon;

import com.example.order.common.FieldErrorDetail;
import com.example.order.common.Problems;
import com.example.order.common.Times;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {

    private static final String UNIQUE_CODE_CONSTRAINT = "uk_coupons_code";

    private final CouponRepository coupons;
    private final Clock clock;

    public CouponService(CouponRepository coupons, Clock clock) {
        this.coupons = coupons;
        this.clock = clock;
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest r) {
        validateCrossField(r);
        if (coupons.existsByCode(r.code())) {
            throw Problems.couponCodeDuplicate(r.code());
        }
        Coupon coupon = new Coupon(r.code(), r.type(), r.value(),
                r.minOrderAmount() == null ? 0L : r.minOrderAmount(), r.maxDiscountAmount(),
                r.totalQuantity(), r.validFrom(), r.validUntil(), Times.now(clock));
        try {
            return CouponResponse.from(coupons.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            Throwable cause = e.getMostSpecificCause();
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            if (message.contains(UNIQUE_CODE_CONSTRAINT)) {
                throw Problems.couponCodeDuplicate(r.code());
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return coupons.findByCode(code).map(CouponResponse::from).orElseThrow(() -> Problems.couponNotFound(code));
    }

    private static void validateCrossField(CreateCouponRequest r) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (r.type() == CouponType.RATE && r.value() > 100) {
            errors.add(new FieldErrorDetail("value", "RATE coupon value must be within 1..100"));
        }
        if (!r.validFrom().isBefore(r.validUntil())) {
            errors.add(new FieldErrorDetail("validUntil", "validFrom must be before validUntil"));
        }
        if (!errors.isEmpty()) {
            throw Problems.validationFailed(errors);
        }
    }
}
