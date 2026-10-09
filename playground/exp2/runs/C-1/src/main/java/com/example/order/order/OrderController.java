package com.example.order.order;

import com.example.order.idempotency.IdempotencyScope;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.idempotency.StoredResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderLifecycleService lifecycleService;
    private final IdempotencyService idempotency;

    public OrderController(OrderService orderService, OrderLifecycleService lifecycleService,
                           IdempotencyService idempotency) {
        this.orderService = orderService;
        this.lifecycleService = lifecycleService;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader("X-User-Id") @NotBlank @Size(max = 50) String userId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 64) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        String fingerprint = idempotency.fingerprint(userId, "/api/orders", request);
        StoredResponse stored = idempotency.execute(IdempotencyScope.CREATE_ORDER, idempotencyKey, fingerprint,
                recordId -> orderService.create(userId, request, recordId));
        return toResponse(stored);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return orderService.get(id);
    }

    @GetMapping
    public OrderPageResponse list(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String cursor) {
        return orderService.list(userId, status, size, cursor);
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<String> pay(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 64) String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody PayRequest request) {
        String fingerprint = idempotency.fingerprint(userId, "/api/orders/" + id + "/pay", request);
        StoredResponse stored = idempotency.execute(IdempotencyScope.PAY_ORDER, idempotencyKey, fingerprint,
                recordId -> lifecycleService.pay(id, request.cardToken(), idempotencyKey, recordId));
        return toResponse(stored);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id) {
        return lifecycleService.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable Long id) {
        return lifecycleService.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable Long id) {
        return lifecycleService.deliver(id);
    }

    private static ResponseEntity<String> toResponse(StoredResponse stored) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.valueOf(stored.status()))
                .contentType(MediaType.APPLICATION_JSON);
        if (stored.location() != null) {
            builder.header(HttpHeaders.LOCATION, stored.location());
        }
        return builder.body(stored.body());
    }
}
