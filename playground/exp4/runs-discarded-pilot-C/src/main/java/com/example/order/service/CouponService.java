package com.example.order.service;

import com.example.order.config.AppClock;
import com.example.order.domain.Coupon;
import com.example.order.domain.CouponType;
import com.example.order.repository.CouponRepository;
import com.example.order.web.dto.CouponCreateRequest;
import com.example.order.web.dto.CouponResponse;
import com.example.order.web.error.ApiException;
import com.example.order.web.error.ErrorCode;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {

    private final CouponRepository coupons;
    private final AppClock clock;

    public CouponService(CouponRepository coupons, AppClock clock) {
        this.coupons = coupons;
        this.clock = clock;
    }

    @Transactional
    public CouponResponse create(CouponCreateRequest req) {
        // 400 단계: 필드 간 검증 (Bean Validation 이후, DB 이전)
        if (req.type() == CouponType.RATE && req.value() > 100) {
            throw ApiException.validation("value: must be between 1 and 100 for RATE coupons");
        }
        Instant from = TimeParsing.parseOffsetDateTime(req.validFrom(), "validFrom");
        Instant until = TimeParsing.parseOffsetDateTime(req.validUntil(), "validUntil");
        if (!from.isBefore(until)) {
            throw ApiException.validation("validFrom: must be before validUntil");
        }
        if (coupons.existsByCode(req.code())) {
            throw duplicate();
        }
        Coupon coupon = new Coupon(req.code(), req.type(), req.value(),
                req.minOrderAmount() == null ? 0L : req.minOrderAmount(), req.maxDiscountAmount(),
                req.totalQuantity(), from, until, clock.now());
        try {
            return CouponResponse.from(coupons.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            if (ConstraintNames.violated(e, "uq_coupons_code")) {
                throw duplicate();
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return coupons.findByCode(code).map(CouponResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon not found."));
    }

    private static ApiException duplicate() {
        return new ApiException(ErrorCode.DUPLICATE_COUPON_CODE, "Coupon code already exists.");
    }
}
