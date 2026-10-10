package com.example.order.order;

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

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<OrderResponse> create(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest req) {
        OrderResponse created = service.create(userId, idempotencyKey, req);
        return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    OrderResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping
    OrderPage list(@RequestParam(required = false) String userId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return service.list(userId != null ? userId : headerUserId, status, size, cursor);
    }

    @PostMapping("/{id}/pay")
    OrderResponse pay(@PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PayOrderRequest req) {
        return service.pay(id, idempotencyKey, req.cardToken());
    }

    @PostMapping("/{id}/cancel")
    OrderResponse cancel(@PathVariable long id) {
        return service.cancel(id);
    }

    @PostMapping("/{id}/ship")
    OrderResponse ship(@PathVariable long id) {
        return service.ship(id);
    }

    @PostMapping("/{id}/deliver")
    OrderResponse deliver(@PathVariable long id) {
        return service.deliver(id);
    }
}
