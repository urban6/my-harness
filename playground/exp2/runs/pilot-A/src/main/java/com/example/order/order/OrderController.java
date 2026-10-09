package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.idempotency.IdempotencyScope;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.idempotency.IdempotencyService.StoredResponse;
import com.example.order.order.OrderDtos.CreateOrderRequest;
import com.example.order.order.OrderDtos.OrderPage;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.order.OrderDtos.PayRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final String USER_ID = "X-User-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public OrderController(OrderService orderService, OrderQueryService orderQueryService,
                           IdempotencyService idempotency, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(value = USER_ID, required = false) String userId,
                                         @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                         @Valid @RequestBody CreateOrderRequest request) {
        validateUserId(userId);
        validateIdempotencyKey(key);
        String fingerprint = IdempotencyService.fingerprint(userId, "/api/orders", toJson(request));
        return idempotency.execute(IdempotencyScope.CREATE_ORDER, key, fingerprint, () -> {
            OrderResponse order = orderService.create(userId, request);
            return new StoredResponse(201, toJson(order), "/api/orders/" + order.id());
        });
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) {
        return orderService.get(id);
    }

    @GetMapping
    public OrderPage list(@RequestParam(required = false) String userId,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) String size,
                          @RequestParam(required = false) String cursor) {
        OrderStatus statusFilter = null;
        if (status != null) {
            try {
                statusFilter = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("status must be one of the order statuses");
            }
        }
        int pageSize = 20;
        if (size != null) {
            try {
                pageSize = Integer.parseInt(size);
            } catch (NumberFormatException e) {
                throw ApiException.validation("size must be an integer between 1 and 100");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw ApiException.validation("size must be an integer between 1 and 100");
            }
        }
        OrderCursor position = cursor == null ? null : OrderCursor.decode(cursor);
        return orderQueryService.list(userId, statusFilter, pageSize, position);
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<String> pay(@PathVariable long id,
                                      @RequestHeader(value = USER_ID, required = false) String userId,
                                      @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                      @Valid @RequestBody PayRequest request) {
        validateIdempotencyKey(key);
        String fingerprint = IdempotencyService.fingerprint(userId, "/api/orders/" + id + "/pay", toJson(request));
        return idempotency.execute(IdempotencyScope.PAY_ORDER, key, fingerprint, () ->
                new StoredResponse(200, toJson(orderService.pay(id, key, request.cardToken())), null));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable long id) {
        return orderService.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable long id) {
        return orderService.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable long id) {
        return orderService.deliver(id);
    }

    private static void validateUserId(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > 50) {
            throw ApiException.validation("X-User-Id header must be 1-50 non-blank characters");
        }
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 64) {
            throw ApiException.validation("Idempotency-Key header must be 1-64 characters");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
