package com.example.order.order;

import java.net.URI;

import com.example.order.common.error.InvalidRequestException;
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
    private final OrderPaymentService orderPaymentService;

    public OrderController(OrderService orderService, OrderPaymentService orderPaymentService) {
        this.orderService = orderService;
        this.orderPaymentService = orderPaymentService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestHeader("X-User-Id") long userId,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @Valid @RequestBody CreateOrderRequest request) {
        requireUserId(userId);
        requireIdempotencyKey(idempotencyKey);
        OrderResponse created = orderService.create(userId, idempotencyKey, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) {
        return orderService.get(id);
    }

    @GetMapping
    public OrderPage list(@RequestParam(required = false) Long userId,
                          @RequestParam(required = false) OrderStatus status,
                          @RequestParam(defaultValue = "20") int size,
                          @RequestParam(required = false) String cursor) {
        return orderService.list(userId, status, size, cursor);
    }

    @PostMapping("/{id}/pay")
    public OrderResponse pay(@PathVariable long id,
                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                             @Valid @RequestBody PayRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return orderPaymentService.pay(id, idempotencyKey, request.cardToken());
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable long id) {
        return orderPaymentService.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable long id) {
        return orderService.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable long id) {
        return orderService.deliver(id);
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new InvalidRequestException("X-User-Id 는 양수여야 합니다.");
        }
    }

    private static void requireIdempotencyKey(String key) {
        if (key.isBlank() || key.length() > 255) {
            throw new InvalidRequestException("Idempotency-Key 는 1~255자여야 합니다.");
        }
    }
}
