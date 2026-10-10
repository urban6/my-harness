package com.example.order.order;

import java.net.URI;

import com.example.order.common.idempotency.IdempotencyExecutor;
import com.example.order.common.idempotency.IdempotencyScope;
import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderPageResponse;
import com.example.order.order.dto.OrderResponse;
import com.example.order.order.dto.PayOrderRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

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
    private final OrderPaymentService orderPaymentService;
    private final IdempotencyExecutor idempotency;

    public OrderController(OrderService orderService, OrderPaymentService orderPaymentService,
                           IdempotencyExecutor idempotency) {
        this.orderService = orderService;
        this.orderPaymentService = orderPaymentService;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(USER_ID) @NotBlank @Size(max = 50) String userId,
            @RequestHeader(IDEMPOTENCY_KEY) @NotEmpty @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            HttpServletRequest httpRequest) {
        return idempotency.execute(IdempotencyScope.CREATE_ORDER, idempotencyKey, userId,
                httpRequest.getRequestURI(), request, () -> {
                    OrderResponse created = orderService.create(userId, request);
                    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                            .path("/{id}").buildAndExpand(created.id()).toUri();
                    return ResponseEntity.created(location).body(created);
                });
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) {
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
            @PathVariable long id,
            @RequestHeader(value = USER_ID, required = false) String userId,
            @RequestHeader(IDEMPOTENCY_KEY) @NotEmpty @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody PayOrderRequest request,
            HttpServletRequest httpRequest) {
        return idempotency.execute(IdempotencyScope.PAY_ORDER, idempotencyKey, userId,
                httpRequest.getRequestURI(), request,
                () -> ResponseEntity.ok(orderPaymentService.pay(id, request.cardToken(), idempotencyKey)));
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
}
