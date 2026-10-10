package com.example.order.web;

import com.example.order.domain.OrderStatus;
import com.example.order.service.OrderService;
import com.example.order.web.Dtos.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<OrderResponse> create(@RequestHeader("X-User-Id") @NotBlank String userId,
                                         @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                         @Valid @RequestBody CreateOrderRequest req) {
        OrderResponse o = service.create(userId, idempotencyKey, req);
        return ResponseEntity.created(URI.create("/api/orders/" + o.id())).body(o);
    }

    @GetMapping("/{id}")
    OrderResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping
    OrderPage list(@RequestParam(required = false) String userId,
                   @RequestParam(required = false) OrderStatus status,
                   @RequestParam(required = false) Integer size,
                   @RequestParam(required = false) Long cursor) {
        return service.list(userId, status, size, cursor);
    }

    @PostMapping("/{id}/pay")
    OrderResponse pay(@PathVariable Long id,
                      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                      @Valid @RequestBody PayRequest req) {
        return service.pay(id, idempotencyKey, req.cardToken());
    }

    @PostMapping("/{id}/cancel")
    OrderResponse cancel(@PathVariable Long id) {
        return service.cancel(id);
    }

    @PostMapping("/{id}/ship")
    OrderResponse ship(@PathVariable Long id) {
        return service.ship(id);
    }

    @PostMapping("/{id}/deliver")
    OrderResponse deliver(@PathVariable Long id) {
        return service.deliver(id);
    }
}
