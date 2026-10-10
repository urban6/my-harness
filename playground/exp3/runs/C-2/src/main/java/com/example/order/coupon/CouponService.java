package com.example.order.coupon;

import com.example.order.common.FieldViolation;
import com.example.order.common.Problems;
import com.example.order.common.TimeSupport;
import com.example.order.coupon.CouponDtos.CouponResponse;
import com.example.order.coupon.CouponDtos.CreateCouponRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class CouponService {

    private static final String UNIQUE_CODE = "uq_coupons_code";

    private final CouponRepository couponRepository;
    private final Clock clock;

    public CouponService(CouponRepository couponRepository, Clock clock) {
        this.couponRepository = couponRepository;
        this.clock = clock;
    }

    /** Not transactional on purpose: the unique violation surfaces from saveAndFlush and is translated to 409. */
    public CouponResponse create(CreateCouponRequest r) {
        validateCrossFields(r);
        Coupon coupon = new Coupon(r.code(), r.type(), r.value(),
                r.minOrderAmount() == null ? 0L : r.minOrderAmount(), r.maxDiscountAmount(),
                r.totalQuantity().intValue(), r.validFrom().truncatedTo(ChronoUnit.MICROS),
                r.validUntil().truncatedTo(ChronoUnit.MICROS), TimeSupport.now(clock));
        try {
            return CouponResponse.from(couponRepository.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            if (isCodeDuplicate(e)) {
                throw Problems.couponCodeDuplicated(r.code());
            }
            throw e;
        }
    }

    public CouponResponse get(String code) {
        return couponRepository.findByCode(code).map(CouponResponse::from)
                .orElseThrow(() -> Problems.couponNotFound(code));
    }

    private static void validateCrossFields(CreateCouponRequest r) {
        List<FieldViolation> errors = new ArrayList<>();
        if (r.type() == CouponType.RATE && r.value() > 100) {
            errors.add(new FieldViolation("value", "must be between 1 and 100 for RATE coupons"));
        }
        if (r.type() == CouponType.FIXED && r.value() > 1_000_000_000L) {
            errors.add(new FieldViolation("value", "must be at most 1000000000 for FIXED coupons"));
        }
        if (!r.validFrom().isBefore(r.validUntil())) {
            errors.add(new FieldViolation("validUntil", "must be after validFrom"));
        }
        if (!errors.isEmpty()) {
            throw Problems.validation(errors);
        }
    }

    private static boolean isCodeDuplicate(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && UNIQUE_CODE.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(UNIQUE_CODE)) {
                return true;
            }
        }
        return false;
    }
}
