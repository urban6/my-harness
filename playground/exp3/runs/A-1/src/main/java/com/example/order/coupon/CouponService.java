package com.example.order.coupon;

import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.order.common.ApiException;

@Service
public class CouponService {

    private final CouponRepository coupons;

    public CouponService(CouponRepository coupons) {
        this.coupons = coupons;
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest req) {
        if (req.type() == CouponType.RATE && req.value() > 100) {
            throw ApiException.badRequest("RATE coupon value must be between 1 and 100");
        }
        if (!req.validUntil().isAfter(req.validFrom())) {
            throw ApiException.badRequest("validUntil must be after validFrom");
        }
        String code = req.code().trim();
        if (coupons.existsByCode(code)) {
            throw ApiException.conflict("Duplicate Coupon", "coupon " + code + " already exists");
        }
        Coupon saved = coupons.saveAndFlush(new Coupon(code, req.type(), req.value(),
                req.minOrderAmount() == null ? 0 : req.minOrderAmount(), req.maxDiscountAmount(),
                req.totalQuantity(), req.validFrom().truncatedTo(ChronoUnit.MICROS),
                req.validUntil().truncatedTo(ChronoUnit.MICROS)));
        return CouponResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return coupons.findByCode(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> ApiException.notFound("coupon " + code + " not found"));
    }
}
