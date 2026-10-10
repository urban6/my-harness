package com.example.order.web;

import com.example.order.service.CouponService;
import com.example.order.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService service;

    public CouponController(CouponService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<CouponResponse> create(@Valid @RequestBody CreateCouponRequest req) {
        CouponResponse c = service.create(req);
        return ResponseEntity.created(URI.create("/api/coupons/" + UriUtils.encodePathSegment(c.code(), StandardCharsets.UTF_8))).body(c);
    }

    @GetMapping("/{code}")
    CouponResponse get(@PathVariable String code) {
        return service.get(code);
    }
}
