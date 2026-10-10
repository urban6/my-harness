package com.example.order.order;

import java.net.URI;

import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderPage;
import com.example.order.order.dto.OrderResponse;
import com.example.order.order.dto.PayRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestHeader("X-User-Id") String userId,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.create(userId, idempotencyKey, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return orderService.get(id);
    }

    @GetMapping
    public OrderPage list(@RequestParam(required = false) String userId,
                          @RequestParam(required = false) OrderStatus status,
                          @RequestParam(required = false) Integer size,
                          @RequestParam(required = false) String cursor) {
        return orderService.list(userId, status, size, cursor);
    }

    @PostMapping("/{id}/pay")
    public OrderResponse pay(@PathVariable Long id,
                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                             @Valid @RequestBody PayRequest request) {
        return orderService.pay(id, idempotencyKey, request);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id) {
        return orderService.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable Long id) {
        return orderService.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable Long id) {
        return orderService.deliver(id);
    }
}
