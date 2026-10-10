package com.example.order.coupon;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {

    private final CouponRepository repository;
    private final Clock clock;

    public CouponService(CouponRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public CouponResponse create(CouponCreateRequest req) {
        if (repository.existsByCode(req.code())) {
            throw duplicate(req.code());
        }
        Coupon coupon = new Coupon(req.code(), req.type(), req.value(),
                req.minOrderAmount() == null ? 0L : req.minOrderAmount(),
                req.maxDiscountAmount(), req.totalQuantity(),
                req.validFromInstant(), req.validUntilInstant(), Times.now(clock));
        try {
            return CouponResponse.from(repository.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            if (msg.contains("uk_coupons_code")) {
                throw duplicate(req.code());
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return repository.findByCode(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon " + code + " not found."));
    }

    private static ApiException duplicate(String code) {
        return new ApiException(ErrorCode.DUPLICATE_COUPON_CODE, "Coupon code " + code + " already exists.");
    }
}
