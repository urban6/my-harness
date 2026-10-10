package com.example.order.coupon;

import com.example.order.common.error.ConflictException;
import com.example.order.common.error.NotFoundException;
import com.example.order.coupon.dto.CouponResponse;
import com.example.order.coupon.dto.CreateCouponRequest;
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
            throw new ConflictException("이미 존재하는 쿠폰 코드입니다: " + request.code());
        }
        Coupon saved = couponRepository.save(new Coupon(
                request.code(), request.type(), request.value(), request.minOrderAmount(),
                request.maxDiscountAmount(), request.totalQuantity(), request.validFrom(), request.validUntil()));
        return CouponResponse.from(saved);
    }

    public CouponResponse getByCode(String code) {
        return couponRepository.findByCode(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> new NotFoundException("쿠폰을 찾을 수 없습니다: code=" + code));
    }
}
