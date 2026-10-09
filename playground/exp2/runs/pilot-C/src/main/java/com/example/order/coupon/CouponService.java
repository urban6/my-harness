package com.example.order.coupon;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import java.time.Clock;
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
    public CouponResponse create(CreateCouponRequest req) {
        if (repository.existsByCode(req.code())) {
            throw new ApiException(ErrorCode.DUPLICATE_COUPON_CODE, "coupon code " + req.code() + " already exists");
        }
        Coupon c = new Coupon(req.code(), req.type(), req.value(),
                req.minOrderAmount() == null ? 0L : req.minOrderAmount(), req.maxDiscountAmount(),
                req.totalQuantity(), Times.normalize(req.validFrom()), Times.normalize(req.validUntil()),
                Times.now(clock));
        return CouponResponse.from(repository.saveAndFlush(c));
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return repository.findByCode(code).map(CouponResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "coupon " + code + " not found"));
    }
}
