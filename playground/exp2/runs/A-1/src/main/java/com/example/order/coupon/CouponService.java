package com.example.order.coupon;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import com.example.order.coupon.CouponDtos.CouponResponse;
import com.example.order.coupon.CouponDtos.CreateCouponRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CouponService {

    private final CouponRepository couponRepository;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CouponService(CouponRepository couponRepository, TransactionTemplate tx, Clock clock) {
        this.couponRepository = couponRepository;
        this.tx = tx;
        this.clock = clock;
    }

    public CouponResponse create(CreateCouponRequest request) {
        validate(request);
        try {
            return tx.execute(status -> {
                if (couponRepository.existsById(request.code())) {
                    throw duplicate(request.code());
                }
                Coupon coupon = new Coupon(
                        request.code(),
                        request.type(),
                        request.value(),
                        request.minOrderAmount() == null ? 0L : request.minOrderAmount(),
                        request.maxDiscountAmount(),
                        request.totalQuantity(),
                        request.validFrom().toInstant().truncatedTo(ChronoUnit.MICROS),
                        request.validUntil().toInstant().truncatedTo(ChronoUnit.MICROS),
                        Times.now(clock));
                couponRepository.saveAndFlush(coupon);
                return CouponResponse.from(coupon);
            });
        } catch (DataIntegrityViolationException e) {
            // 같은 코드가 동시에 등록된 경우
            throw duplicate(request.code());
        }
    }

    public CouponResponse get(String code) {
        return couponRepository.findById(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon " + code + " not found"));
    }

    private static void validate(CreateCouponRequest request) {
        long value = request.value();
        if (request.type() == CouponType.FIXED && value < 1) {
            throw ApiException.validation("value: FIXED coupon value must be at least 1");
        }
        if (request.type() == CouponType.RATE && (value < 1 || value > 100)) {
            throw ApiException.validation("value: RATE coupon value must be between 1 and 100");
        }
        if (!request.validFrom().toInstant().isBefore(request.validUntil().toInstant())) {
            throw ApiException.validation("validFrom must be before validUntil");
        }
    }

    private static ApiException duplicate(String code) {
        return new ApiException(ErrorCode.DUPLICATE_COUPON_CODE, "Coupon " + code + " already exists");
    }
}
