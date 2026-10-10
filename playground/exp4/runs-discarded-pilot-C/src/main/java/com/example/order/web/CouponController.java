package com.example.order.web;

import com.example.order.service.CouponService;
import com.example.order.service.ExpiryService;
import com.example.order.web.dto.CouponCreateRequest;
import com.example.order.web.dto.CouponResponse;
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

    private final CouponService coupons;
    private final ExpiryService expiry;

    public CouponController(CouponService coupons, ExpiryService expiry) {
        this.coupons = coupons;
        this.expiry = expiry;
    }

    @PostMapping("/api/coupons")
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CouponCreateRequest request) {
        CouponResponse created = coupons.create(request);
        return ResponseEntity.created(URI.create("/api/coupons/" + created.code())).body(created);
    }

    @GetMapping("/api/coupons/{code}")
    public CouponResponse get(@PathVariable String code) {
        expiry.expireDue();
        return coupons.get(code);
    }
}
