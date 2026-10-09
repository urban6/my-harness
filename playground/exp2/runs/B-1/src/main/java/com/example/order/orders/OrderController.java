package com.example.order.orders;

import com.example.order.idempotency.IdempotencyScope;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.idempotency.RequestFingerprint;
import com.example.order.orders.dto.CreateOrderRequest;
import com.example.order.orders.dto.OrderPageResponse;
import com.example.order.orders.dto.OrderResponse;
import com.example.order.orders.dto.PayOrderRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final String USER_ID = "X-User-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final OrderService orderService;
    private final OrderLifecycleService lifecycleService;
    private final IdempotencyService idempotencyService;

    public OrderController(OrderService orderService, OrderLifecycleService lifecycleService,
                           IdempotencyService idempotencyService) {
        this.orderService = orderService;
        this.lifecycleService = lifecycleService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(USER_ID) @NotBlank @Size(max = 50) String userId,
            @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 1, max = 64) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        RequestFingerprint fingerprint = new RequestFingerprint(userId, "/api/orders", request);
        return idempotencyService.execute(IdempotencyScope.CREATE_ORDER, idempotencyKey, fingerprint, () -> {
            OrderResponse created = orderService.create(userId, request);
            URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .path("/{id}").buildAndExpand(created.id()).toUri();
            return ResponseEntity.created(location).body(created);
        });
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
    public ResponseEntity<?> pay(
            @PathVariable Long id,
            @RequestHeader(value = USER_ID, required = false) String userId,
            @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 1, max = 64) String idempotencyKey,
            @Valid @RequestBody PayOrderRequest request) {
        RequestFingerprint fingerprint = new RequestFingerprint(userId, "/api/orders/" + id + "/pay", request);
        return idempotencyService.execute(IdempotencyScope.PAY_ORDER, idempotencyKey, fingerprint,
                () -> ResponseEntity.ok(lifecycleService.pay(id, request.cardToken(), idempotencyKey)));
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
}
