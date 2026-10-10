package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Violations;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.idempotency.StoredResponse;
import com.example.order.order.OrderDtos.CreateOrderRequest;
import com.example.order.order.OrderDtos.OrderPage;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.order.OrderDtos.PayRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
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

    private static final String SCOPE_CREATE = "ORDER_CREATE";
    private static final String SCOPE_PAY = "ORDER_PAY";

    private final OrderService orders;
    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public OrderController(OrderService orders, PaymentService payments, IdempotencyService idempotency,
                           ObjectMapper mapper) {
        this.orders = orders;
        this.payments = payments;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @PostMapping
    ResponseEntity<byte[]> create(
            @RequestHeader(name = "X-User-Id", required = false) String userId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody CreateOrderRequest req,
            HttpServletRequest http) {
        OrderService.validateCreate(userId, key, req); // 400 → (멱등 422·409) → 404 → 409
        String fingerprint = IdempotencyService.fingerprint(http.getMethod(), http.getRequestURI(), userId, json(req));
        StoredResponse response = idempotency.execute(SCOPE_CREATE, key, fingerprint, () -> {
            OrderResponse created = orders.create(userId, req);
            String location = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/api/orders/{id}").buildAndExpand(created.id()).toUriString();
            return new StoredResponse(201, json(created), location);
        });
        return response.toResponseEntity();
    }

    @GetMapping("/{id}")
    OrderResponse get(@PathVariable String id) {
        return orders.get(id);
    }

    @GetMapping
    OrderPage list(@RequestParam(required = false) String userId,
                   @RequestParam(required = false) String status,
                   @RequestParam(required = false) String size,
                   @RequestParam(required = false) String cursor) {
        return orders.list(userId, status, size, cursor);
    }

    @PostMapping("/{id}/pay")
    ResponseEntity<byte[]> pay(
            @PathVariable String id,
            @RequestHeader(name = "X-User-Id", required = false) String userId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody PayRequest req,
            HttpServletRequest http) {
        Violations v = new Violations();
        OrderService.validateIdempotencyKey(v, key);
        v.check(req.cardToken() != null && !req.cardToken().isBlank(), "cardToken must not be blank");
        v.throwIfAny();
        String fingerprint = IdempotencyService.fingerprint(http.getMethod(), http.getRequestURI(), userId, json(req));
        StoredResponse response = idempotency.execute(SCOPE_PAY, key, fingerprint,
                () -> new StoredResponse(200, json(payments.pay(id, req.cardToken(), key)), null));
        return response.toResponseEntity();
    }

    @PostMapping("/{id}/cancel")
    OrderResponse cancel(@PathVariable String id) {
        return payments.cancel(id);
    }

    @PostMapping("/{id}/ship")
    OrderResponse ship(@PathVariable String id) {
        return orders.ship(id);
    }

    @PostMapping("/{id}/deliver")
    OrderResponse deliver(@PathVariable String id) {
        return orders.deliver(id);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Cannot serialize payload");
        }
    }
}
