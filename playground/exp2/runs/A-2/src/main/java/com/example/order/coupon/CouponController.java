package com.example.order.coupon;

import com.example.order.common.ApiException;
import com.example.order.common.Times;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{4,20}");

    public record CreateCouponRequest(String code, String type, Long value, Long minOrderAmount,
                                      Long maxDiscountAmount, Long totalQuantity,
                                      OffsetDateTime validFrom, OffsetDateTime validUntil) {
    }

    public record CouponResponse(String code, CouponType type, long value, long minOrderAmount,
                                 Long maxDiscountAmount, int totalQuantity, int usedCount,
                                 OffsetDateTime validFrom, OffsetDateTime validUntil) {
        static CouponResponse of(Coupon c) {
            return new CouponResponse(c.getCode(), c.getType(), c.getValue(), c.getMinOrderAmount(),
                    c.getMaxDiscountAmount(), c.getTotalQuantity(), c.getUsedCount(),
                    Times.toOffset(c.getValidFrom()), Times.toOffset(c.getValidUntil()));
        }
    }

    private final CouponRepository coupons;
    private final TransactionTemplate tx;

    public CouponController(CouponRepository coupons, TransactionTemplate tx) {
        this.coupons = coupons;
        this.tx = tx;
    }

    @PostMapping
    public ResponseEntity<CouponResponse> create(@RequestBody CreateCouponRequest req) {
        Coupon coupon = validate(req);
        CouponResponse body;
        try {
            body = tx.execute(status -> {
                if (coupons.existsById(coupon.getCode())) {
                    throw duplicate(coupon.getCode());
                }
                return CouponResponse.of(coupons.saveAndFlush(coupon));
            });
        } catch (DataIntegrityViolationException e) {
            throw duplicate(coupon.getCode());
        }
        return ResponseEntity.created(URI.create("/api/coupons/" + coupon.getCode())).body(body);
    }

    @GetMapping("/{code}")
    public CouponResponse get(@PathVariable String code) {
        return coupons.findById(code)
                .map(CouponResponse::of)
                .orElseThrow(() -> ApiException.notFound("COUPON_NOT_FOUND", "Coupon " + code + " not found"));
    }

    private static ApiException duplicate(String code) {
        return ApiException.conflict("DUPLICATE_COUPON_CODE", "Coupon " + code + " already exists");
    }

    private static Coupon validate(CreateCouponRequest req) {
        if (req.code() == null || !CODE.matcher(req.code()).matches()) {
            throw ApiException.validation("code must be 4-20 uppercase letters or digits");
        }
        CouponType type;
        try {
            type = req.type() == null ? null : CouponType.valueOf(req.type());
        } catch (IllegalArgumentException e) {
            type = null;
        }
        if (type == null) {
            throw ApiException.validation("type must be FIXED or RATE");
        }
        if (req.value() == null || req.value() < 1 || (type == CouponType.RATE && req.value() > 100)) {
            throw ApiException.validation(type == CouponType.RATE
                    ? "value must be between 1 and 100 for RATE coupons"
                    : "value must be at least 1 for FIXED coupons");
        }
        long minOrderAmount = req.minOrderAmount() == null ? 0 : req.minOrderAmount();
        if (minOrderAmount < 0) {
            throw ApiException.validation("minOrderAmount must be at least 0");
        }
        if (req.maxDiscountAmount() != null && req.maxDiscountAmount() < 1) {
            throw ApiException.validation("maxDiscountAmount must be at least 1");
        }
        if (req.totalQuantity() == null || req.totalQuantity() < 1 || req.totalQuantity() > Integer.MAX_VALUE) {
            throw ApiException.validation("totalQuantity must be at least 1");
        }
        if (req.validFrom() == null || req.validUntil() == null || !req.validFrom().isBefore(req.validUntil())) {
            throw ApiException.validation("validFrom must be before validUntil");
        }
        return new Coupon(req.code(), type, req.value(), minOrderAmount, req.maxDiscountAmount(),
                req.totalQuantity().intValue(), req.validFrom().toInstant(), req.validUntil().toInstant());
    }
}
