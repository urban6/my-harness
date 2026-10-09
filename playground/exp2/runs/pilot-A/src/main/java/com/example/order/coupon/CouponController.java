package com.example.order.coupon;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import com.example.order.coupon.CouponDtos.CouponResponse;
import com.example.order.coupon.CouponDtos.CreateCouponRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponRepository coupons;
    private final Clock clock;

    public CouponController(CouponRepository coupons, Clock clock) {
        this.coupons = coupons;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CreateCouponRequest request) {
        if (coupons.existsByCode(request.code())) {
            throw duplicate(request.code());
        }
        Coupon coupon = new Coupon(request.code(), request.type(), request.value(),
                request.minOrderAmount() == null ? 0 : request.minOrderAmount(),
                request.maxDiscountAmount(), request.totalQuantity(),
                request.validFrom(), request.validUntil(), Times.now(clock));
        Coupon saved;
        try {
            saved = coupons.saveAndFlush(coupon);
        } catch (DataIntegrityViolationException e) {
            throw duplicate(request.code()); // 동시에 같은 코드가 등록된 경우
        }
        return ResponseEntity.created(URI.create("/api/coupons/" + saved.getCode()))
                .body(CouponResponse.from(saved));
    }

    @GetMapping("/{code}")
    @Transactional(readOnly = true)
    public CouponResponse get(@PathVariable String code) {
        return coupons.findByCode(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon " + code + " not found"));
    }

    private static ApiException duplicate(String code) {
        return new ApiException(ErrorCode.DUPLICATE_COUPON_CODE, "Coupon code " + code + " already exists");
    }
}
