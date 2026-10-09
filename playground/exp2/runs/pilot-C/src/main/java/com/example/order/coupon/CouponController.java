package com.example.order.coupon;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CouponController {
    private final CouponService service;

    public CouponController(CouponService service) {
        this.service = service;
    }

    @PostMapping("/api/coupons")
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CreateCouponRequest req) {
        req.validateCross();
        CouponResponse res = service.create(req);
        return ResponseEntity.created(URI.create("/api/coupons/" + res.code())).body(res);
    }

    @GetMapping("/api/coupons/{code}")
    public CouponResponse get(@PathVariable String code) {
        return service.get(code);
    }
}
