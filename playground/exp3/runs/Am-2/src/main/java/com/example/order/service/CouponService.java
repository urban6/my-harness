package com.example.order.service;

import com.example.order.domain.Coupon;
import com.example.order.domain.CouponType;
import com.example.order.repo.CouponRepository;
import com.example.order.web.ApiException;
import com.example.order.web.Dtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {

    private final CouponRepository coupons;

    public CouponService(CouponRepository coupons) {
        this.coupons = coupons;
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest r) {
        if (r.type() == CouponType.RATE && r.value() > 100) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Bad Request", "RATE value must be between 1 and 100");
        }
        if (!r.validFrom().isBefore(r.validUntil())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Bad Request", "validFrom must be before validUntil");
        }
        if (coupons.existsByCode(r.code())) {
            throw ApiException.conflict("Conflict", "Coupon code already exists: " + r.code());
        }
        Coupon c = coupons.saveAndFlush(new Coupon(r.code(), r.type(), r.value(), r.minOrderAmount(),
                r.maxDiscountAmount(), r.totalQuantity(), r.validFrom(), r.validUntil()));
        return CouponResponse.of(c);
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return coupons.findByCode(code).map(CouponResponse::of).orElseThrow(() -> ApiException.notFound("Coupon", code));
    }
}
