package com.example.order.coupon;

import com.example.order.common.error.CouponNotFoundException;
import com.example.order.common.error.DuplicateCouponCodeException;
import com.example.order.common.error.RequestValidationException;
import com.example.order.common.web.TimeFormats;
import com.example.order.coupon.dto.CouponResponse;
import com.example.order.coupon.dto.CreateCouponRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {

    private final CouponRepository couponRepository;
    private final Clock clock;

    public CouponService(CouponRepository couponRepository, Clock clock) {
        this.couponRepository = couponRepository;
        this.clock = clock;
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest request) {
        // 단일 필드 검증(Bean Validation)이 모두 통과한 뒤의 교차 검증. 모두 400.
        CouponType type = CouponType.valueOf(request.type());
        if (type == CouponType.RATE && request.value() > 100) {
            throw new RequestValidationException("value", "RATE 쿠폰의 value는 1~100 이어야 합니다");
        }
        Instant validFrom = TimeFormats.parseOffsetDateTime("validFrom", request.validFrom());
        Instant validUntil = TimeFormats.parseOffsetDateTime("validUntil", request.validUntil());
        if (!validFrom.isBefore(validUntil)) {
            throw new RequestValidationException("validUntil", "validFrom 보다 이후여야 합니다");
        }
        if (couponRepository.existsByCode(request.code())) {
            throw new DuplicateCouponCodeException("이미 존재하는 쿠폰 코드입니다: " + request.code());
        }
        Coupon coupon = new Coupon(request.code(), type, request.value(),
                request.minOrderAmount() == null ? 0L : request.minOrderAmount(),
                request.maxDiscountAmount(), request.totalQuantity(), validFrom, validUntil,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        // 동시 등록 경합은 uk_coupons_code 위반으로 드러나며 핸들러가 409로 매핑한다.
        return CouponResponse.from(couponRepository.saveAndFlush(coupon));
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return couponRepository.findByCode(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> new CouponNotFoundException("쿠폰 " + code + "을(를) 찾을 수 없습니다."));
    }
}
