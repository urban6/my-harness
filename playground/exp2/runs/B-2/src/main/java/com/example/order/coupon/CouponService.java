package com.example.order.coupon;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import com.example.order.coupon.dto.CouponResponse;
import com.example.order.coupon.dto.CreateCouponRequest;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository couponRepository;

    public CouponService(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest request) {
        if (couponRepository.existsByCode(request.code())) {
            throw duplicate(request.code());
        }
        Coupon coupon = new Coupon(
                request.code(),
                request.type(),
                request.value(),
                request.minOrderAmount() == null ? 0 : request.minOrderAmount(),
                request.maxDiscountAmount(),
                request.totalQuantity(),
                request.validFrom().toInstant(),
                request.validUntil().toInstant());
        try {
            return CouponResponse.from(couponRepository.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 code로 등록된 경우 — unique 제약이 막는다.
            throw duplicate(request.code());
        }
    }

    public CouponResponse get(String code) {
        return couponRepository.findByCode(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND, "쿠폰을 찾을 수 없습니다: code=" + code));
    }

    private static BusinessException duplicate(String code) {
        return new BusinessException(ErrorCode.DUPLICATE_COUPON_CODE, "이미 존재하는 쿠폰 코드입니다: code=" + code);
    }
}
