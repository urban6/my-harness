package com.example.order.coupon;

import com.example.order.common.error.ApiException;
import com.example.order.common.error.ErrorCode;
import java.time.temporal.ChronoUnit;
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
    public CouponResponse create(CouponCreateRequest r) {
        if (couponRepository.existsByCode(r.code())) {
            throw duplicate(r.code());
        }
        Coupon coupon = Coupon.create(
                r.code(), r.type(), r.value(),
                r.minOrderAmount() == null ? 0L : r.minOrderAmount(),
                r.maxDiscountAmount(), r.totalQuantity(),
                r.validFrom().toInstant().truncatedTo(ChronoUnit.MICROS),
                r.validUntil().toInstant().truncatedTo(ChronoUnit.MICROS));
        try {
            return CouponResponse.from(couponRepository.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            throw duplicate(r.code());
        }
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return couponRepository.findByCode(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "쿠폰을 찾을 수 없습니다: code=" + code));
    }

    private static ApiException duplicate(String code) {
        return new ApiException(ErrorCode.DUPLICATE_COUPON_CODE, "이미 존재하는 쿠폰 코드입니다: " + code);
    }
}
