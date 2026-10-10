package com.example.order.web;

import com.example.order.domain.OrderStatus;
import com.example.order.service.CatalogService;
import com.example.order.service.OrderService;
import com.example.order.web.Dtos.*;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

public final class Controllers {

    private Controllers() {
    }

    @RestController
    @RequestMapping("/api/products")
    public static class ProductController {
        private final CatalogService catalog;

        public ProductController(CatalogService catalog) {
            this.catalog = catalog;
        }

        @PostMapping
        ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest req) {
            ProductResponse p = catalog.createProduct(req);
            return ResponseEntity.created(URI.create("/api/products/" + p.id())).body(p);
        }

        @GetMapping("/{id}")
        ProductResponse get(@PathVariable long id) {
            return catalog.getProduct(id);
        }
    }

    @RestController
    @RequestMapping("/api/coupons")
    public static class CouponController {
        private final CatalogService catalog;

        public CouponController(CatalogService catalog) {
            this.catalog = catalog;
        }

        @PostMapping
        ResponseEntity<CouponResponse> create(@Valid @RequestBody CouponRequest req) {
            CouponResponse c = catalog.createCoupon(req);
            return ResponseEntity.created(URI.create("/api/coupons/" + c.code())).body(c);
        }

        @GetMapping("/{code}")
        CouponResponse get(@PathVariable String code) {
            return catalog.getCoupon(code);
        }
    }

    @RestController
    @RequestMapping("/api/orders")
    public static class OrderController {
        private final OrderService orders;

        public OrderController(OrderService orders) {
            this.orders = orders;
        }

        @PostMapping
        ResponseEntity<OrderResponse> create(@RequestHeader("X-User-Id") String userId,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody OrderRequest req) {
            requireText(userId, "X-User-Id");
            requireText(key, "Idempotency-Key");
            OrderResponse o = orders.create(userId.trim(), key.trim(), req);
            return ResponseEntity.created(URI.create("/api/orders/" + o.id())).body(o);
        }

        @GetMapping("/{id}")
        OrderResponse get(@PathVariable long id) {
            return orders.get(id);
        }

        @GetMapping
        OrderPage list(@RequestParam(required = false) String userId,
                       @RequestParam(required = false) OrderStatus status,
                       @RequestParam(defaultValue = "20") int size,
                       @RequestParam(required = false) String cursor) {
            return orders.list(userId, status, size, cursor);
        }

        @PostMapping("/{id}/pay")
        OrderResponse pay(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
                          @Valid @RequestBody PayRequest req) {
            requireText(key, "Idempotency-Key");
            return orders.pay(id, key.trim(), req.cardToken());
        }

        @PostMapping("/{id}/cancel")
        OrderResponse cancel(@PathVariable long id) {
            return orders.cancel(id);
        }

        @PostMapping("/{id}/ship")
        OrderResponse ship(@PathVariable long id) {
            return orders.ship(id);
        }

        @PostMapping("/{id}/deliver")
        OrderResponse deliver(@PathVariable long id) {
            return orders.deliver(id);
        }

        private static void requireText(String v, String name) {
            if (v == null || v.isBlank()) {
                throw ApiException.badRequest(name + " header must not be blank");
            }
        }
    }
}
