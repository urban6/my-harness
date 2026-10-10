package com.example.order.coupon;

import com.example.order.coupon.CouponDtos.CouponResponse;
import com.example.order.coupon.CouponDtos.CreateCouponRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService service;

    public CouponController(CouponService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CouponResponse> create(@RequestBody CreateCouponRequest request) {
        CouponResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/coupons/" + created.code())).body(created);
    }

    @GetMapping("/{code}")
    public CouponResponse get(@PathVariable String code) {
        return service.get(code);
    }
}
