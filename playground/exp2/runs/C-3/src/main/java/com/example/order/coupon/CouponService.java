package com.example.order.coupon;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ConstraintNames;
import com.example.order.common.error.ErrorCode;
import com.example.order.common.time.Times;
import com.example.order.coupon.dto.CouponResponse;
import com.example.order.coupon.dto.CreateCouponRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {

    private final CouponRepository couponRepository;

    public CouponService(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest req) {
        if (couponRepository.existsByCode(req.code())) {
            throw duplicate(req.code());
        }
        Coupon coupon = new Coupon(
                req.code(),
                CouponType.valueOf(req.type()),
                req.value(),
                req.minOrderAmount(),
                req.maxDiscountAmount(),
                req.totalQuantity(),
                Times.truncate(req.validFrom().toInstant()),
                Times.truncate(req.validUntil().toInstant()));
        try {
            return CouponResponse.from(couponRepository.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            if (ConstraintNames.UQ_COUPONS_CODE.equals(ConstraintNames.of(e))) {
                throw duplicate(req.code());
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        if (code.indexOf('\u0000') >= 0) { // PostgreSQL은 NUL 문자열을 거부(22021) -> DB에 닿기 전에 미존재 처리
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND, "쿠폰을 찾을 수 없습니다.");
        }
        return couponRepository.findByCode(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND, "쿠폰을 찾을 수 없습니다: code=" + code));
    }

    private static BusinessException duplicate(String code) {
        return new BusinessException(ErrorCode.DUPLICATE_COUPON_CODE, "이미 존재하는 쿠폰 코드입니다: " + code);
    }
}
