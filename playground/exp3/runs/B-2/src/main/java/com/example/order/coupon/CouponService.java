package com.example.order.coupon;

import java.time.Instant;

import com.example.order.common.error.DomainException;
import com.example.order.coupon.dto.CouponResponse;
import com.example.order.coupon.dto.CreateCouponRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository couponRepository;

    public CouponService(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest r) {
        if (r.type() == CouponType.RATE && r.value() > 100) {
            throw DomainException.invalid("INVALID_COUPON", "정률 쿠폰의 value는 1~100 이어야 합니다.");
        }
        if (!r.validFrom().isBefore(r.validUntil())) {
            throw DomainException.invalid("INVALID_COUPON", "validFrom은 validUntil보다 앞서야 합니다.");
        }
        if (couponRepository.existsByCode(r.code())) {
            throw duplicate(r.code());
        }
        try {
            Coupon saved = couponRepository.saveAndFlush(new Coupon(r.code(), r.type(), r.value(),
                    r.minOrderAmount(), r.maxDiscountAmount(), r.totalQuantity(), r.validFrom(), r.validUntil()));
            return CouponResponse.from(saved);
        } catch (DataIntegrityViolationException e) {   // 동시 생성 경합: unique 제약이 최종 방어선
            throw duplicate(r.code());
        }
    }

    public CouponResponse get(String code) {
        return CouponResponse.from(couponRepository.findByCode(code).orElseThrow(() -> notFound(code)));
    }

    /** 주문 트랜잭션 안에서 쿠폰을 검증하고 사용 수량을 1 올린 뒤 할인액을 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long apply(String code, long subtotal, Instant now) {
        Coupon coupon = couponRepository.findByCode(code).orElseThrow(() -> notFound(code));
        if (!coupon.isValidAt(now)) {
            throw DomainException.unprocessable("COUPON_NOT_VALID", "사용 기간이 아닌 쿠폰입니다: " + code);
        }
        if (!coupon.isSatisfiedBy(subtotal)) {
            throw DomainException.unprocessable("COUPON_MIN_ORDER_NOT_MET", "최소 주문 금액을 충족하지 않습니다: " + code);
        }
        if (couponRepository.use(code) == 0) {
            throw DomainException.conflict("COUPON_EXHAUSTED", "소진된 쿠폰입니다: " + code);
        }
        return coupon.discountFor(subtotal);
    }

    /** 주문이 판매로 이어지지 못했을 때(취소·만료·실패·환불) 사용 수량을 되돌린다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(String code) {
        if (couponRepository.release(code) == 0) {
            throw new IllegalStateException("쿠폰 반환 실패: code=" + code);
        }
    }

    private static DomainException notFound(String code) {
        return DomainException.notFound("COUPON_NOT_FOUND", "쿠폰을 찾을 수 없습니다: " + code);
    }

    private static DomainException duplicate(String code) {
        return DomainException.conflict("COUPON_CODE_DUPLICATED", "이미 존재하는 쿠폰 코드입니다: " + code);
    }
}
