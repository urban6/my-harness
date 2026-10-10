package com.example.order.coupon;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService service;

    public CouponController(CouponService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<CouponResponse> create(@Valid @RequestBody CreateCouponRequest req) {
        CouponResponse created = service.create(req);
        URI location = UriComponentsBuilder.fromPath("/api/coupons/{code}").buildAndExpand(created.code())
                .encode().toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{code}")
    CouponResponse get(@PathVariable String code) {
        return service.get(code);
    }
}
