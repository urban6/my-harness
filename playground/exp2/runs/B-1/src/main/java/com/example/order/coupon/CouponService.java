package com.example.order.coupon;

import com.example.order.common.Times;
import com.example.order.coupon.dto.CouponResponse;
import com.example.order.coupon.dto.CreateCouponRequest;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository couponRepository;
    private final Clock clock;

    public CouponService(CouponRepository couponRepository, Clock clock) {
        this.couponRepository = couponRepository;
        this.clock = clock;
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest request) {
        if (couponRepository.existsByCode(request.code())) {
            throw new DuplicateCouponCodeException(request.code());
        }
        Coupon coupon = new Coupon(request.code(), request.type(), request.value(),
                request.minOrderAmountOrDefault(), request.maxDiscountAmount(), request.totalQuantity(),
                Times.toInstant(request.validFrom()), Times.toInstant(request.validUntil()), Times.now(clock));
        try {
            return CouponResponse.from(couponRepository.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            // 같은 코드가 동시에 등록된 경우: 유니크 제약이 최종 판정한다.
            throw new DuplicateCouponCodeException(request.code());
        }
    }

    public CouponResponse get(String code) {
        return couponRepository.findByCode(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> new CouponNotFoundException(code));
    }
}
