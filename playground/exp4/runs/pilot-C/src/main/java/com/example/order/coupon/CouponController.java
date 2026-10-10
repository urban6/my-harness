package com.example.order.coupon;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService service;

    public CouponController(CouponService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CouponCreateRequest request) {
        CouponResponse body = service.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/coupons/{code}").buildAndExpand(body.code()).toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{code}")
    public CouponResponse get(@PathVariable String code) {
        return service.get(code);
    }
}
