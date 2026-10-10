package com.example.order.coupon;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Violations;
import com.example.order.coupon.CouponDtos.CouponResponse;
import com.example.order.coupon.CouponDtos.CreateCouponRequest;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{4,20}");

    private final CouponRepository coupons;

    public CouponService(CouponRepository coupons) {
        this.coupons = coupons;
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest req) {
        validate(req);
        if (coupons.existsByCode(req.code())) {
            throw duplicate(req.code());
        }
        Coupon coupon = new Coupon(req.code(), req.type(), req.value(),
                req.minOrderAmount() == null ? 0 : req.minOrderAmount(), req.maxDiscountAmount(),
                req.totalQuantity(),
                req.validFrom().toInstant().truncatedTo(ChronoUnit.MICROS),
                req.validUntil().toInstant().truncatedTo(ChronoUnit.MICROS));
        try {
            return CouponResponse.from(coupons.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            throw duplicate(req.code());
        }
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return coupons.findByCode(code).map(CouponResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon not found: " + code));
    }

    private static ApiException duplicate(String code) {
        return new ApiException(ErrorCode.DUPLICATE_COUPON_CODE, "Coupon code already exists: " + code);
    }

    private static void validate(CreateCouponRequest r) {
        Violations v = new Violations();
        v.check(r.code() != null && CODE.matcher(r.code()).matches(),
                "code must be 4-20 uppercase letters or digits");
        v.check(r.type() != null, "type must be FIXED or RATE");
        if (r.type() == CouponType.FIXED) {
            v.check(r.value() != null && r.value() >= 1, "value must be at least 1 for FIXED");
        } else if (r.type() == CouponType.RATE) {
            v.check(r.value() != null && r.value() >= 1 && r.value() <= 100, "value must be 1-100 for RATE");
        } else {
            v.check(r.value() != null, "value is required");
        }
        v.check(r.minOrderAmount() == null || r.minOrderAmount() >= 0, "minOrderAmount must be at least 0");
        v.check(r.maxDiscountAmount() == null || r.maxDiscountAmount() >= 1,
                "maxDiscountAmount must be at least 1 when given");
        v.check(r.totalQuantity() != null && r.totalQuantity() >= 1, "totalQuantity must be at least 1");
        v.check(r.validFrom() != null, "validFrom is required");
        v.check(r.validUntil() != null, "validUntil is required");
        if (r.validFrom() != null && r.validUntil() != null) {
            v.check(r.validFrom().toInstant().isBefore(r.validUntil().toInstant()),
                    "validFrom must be before validUntil");
        }
        v.throwIfAny();
    }
}
