package com.example.order.coupon;

import com.example.order.common.ApiException;
import com.example.order.coupon.CouponDtos.CouponResponse;
import com.example.order.coupon.CouponDtos.CreateCouponRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {

    private final CouponRepository coupons;
    private final JdbcClient jdbc;

    public CouponService(CouponRepository coupons, JdbcClient jdbc) {
        this.coupons = coupons;
        this.jdbc = jdbc;
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest req) {
        if (req.type() == CouponType.RATE && req.value() > 100) {
            throw ApiException.badRequest("VALIDATION_FAILED", "RATE coupon value must be between 1 and 100");
        }
        if (!req.validFrom().isBefore(req.validUntil())) {
            throw ApiException.badRequest("VALIDATION_FAILED", "validFrom must be before validUntil");
        }
        if (coupons.findByCode(req.code()).isPresent()) {
            throw duplicate(req.code());
        }
        Coupon coupon = new Coupon(req.code(), req.type(), req.value(),
                req.minOrderAmount() == null ? 0 : req.minOrderAmount(), req.maxDiscountAmount(),
                req.totalQuantity(), req.validFrom(), req.validUntil());
        try {
            return CouponResponse.from(coupons.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            throw duplicate(req.code());
        }
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return CouponResponse.from(find(code));
    }

    public Coupon find(String code) {
        return coupons.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("COUPON_NOT_FOUND", "Coupon not found: " + code));
    }

    /** 잔여 수량이 있을 때만 사용 처리(원자적). */
    public boolean tryUse(String code) {
        return jdbc.sql("update coupons set used_count = used_count + 1 "
                        + "where code = :code and used_count < total_quantity")
                .param("code", code).update() == 1;
    }

    /** 사용 처리 취소 (주문 취소·만료·결제 실패·환불). */
    public void release(String code) {
        jdbc.sql("update coupons set used_count = used_count - 1 where code = :code and used_count > 0")
                .param("code", code).update();
    }

    private static ApiException duplicate(String code) {
        return ApiException.conflict("COUPON_CODE_DUPLICATED", "Coupon code already exists: " + code);
    }
}
