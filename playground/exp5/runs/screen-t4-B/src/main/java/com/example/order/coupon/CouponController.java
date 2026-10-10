package com.example.order.coupon;

import com.example.order.common.ApiException;
import com.example.order.common.Times;
import com.example.order.order.ExpiryService;
import com.example.order.tenant.TenantInterceptor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.OffsetDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponRepository coupons;
    private final ExpiryService expiry;

    public CouponController(CouponRepository coupons, ExpiryService expiry) {
        this.coupons = coupons;
        this.expiry = expiry;
    }

    public record CreateCouponRequest(
            @NotNull @Pattern(regexp = "^[A-Z0-9]{4,20}$") String code,
            @NotNull @Pattern(regexp = "^(FIXED|RATE)$") String type,
            @NotNull @Min(1) Long value,
            @Min(0) Long minOrderAmount,
            @Min(1) Long maxDiscountAmount,
            @NotNull @Min(1) Long totalQuantity,
            @NotNull OffsetDateTime validFrom,
            @NotNull OffsetDateTime validUntil) {
    }

    public record CouponResponse(String code, String type, long value, long minOrderAmount, Long maxDiscountAmount,
            long totalQuantity, long usedCount, OffsetDateTime validFrom, OffsetDateTime validUntil) {
        static CouponResponse from(Coupon c) {
            return new CouponResponse(c.code(), c.type(), c.value(), c.minOrderAmount(), c.maxDiscountAmount(),
                    c.totalQuantity(), c.usedCount(), c.validFrom(), c.validUntil());
        }
    }

    @PostMapping
    public ResponseEntity<CouponResponse> create(@RequestAttribute(TenantInterceptor.ATTRIBUTE) String tenantId,
            @Valid @RequestBody CreateCouponRequest req) {
        if ("RATE".equals(req.type()) && req.value() > 100) {
            throw ApiException.validation("RATE coupon value must be between 1 and 100");
        }
        OffsetDateTime from = Times.normalize(req.validFrom());
        OffsetDateTime until = Times.normalize(req.validUntil());
        if (!from.isBefore(until)) {
            throw ApiException.validation("validFrom must be before validUntil");
        }
        Coupon coupon = new Coupon(req.code(), req.type(), req.value(),
                req.minOrderAmount() == null ? 0L : req.minOrderAmount(), req.maxDiscountAmount(),
                req.totalQuantity(), 0L, from, until);
        if (!coupons.insert(tenantId, coupon)) {
            throw ApiException.conflict("DUPLICATE_COUPON_CODE", "coupon " + req.code() + " already exists");
        }
        return ResponseEntity.created(URI.create("/api/coupons/" + coupon.code())).body(CouponResponse.from(coupon));
    }

    @GetMapping("/{code}")
    public CouponResponse get(@RequestAttribute(TenantInterceptor.ATTRIBUTE) String tenantId, @PathVariable String code) {
        expiry.expireDue();
        return coupons.find(tenantId, code).map(CouponResponse::from)
                .orElseThrow(() -> ApiException.notFound("COUPON_NOT_FOUND", "coupon " + code + " not found"));
    }
}
