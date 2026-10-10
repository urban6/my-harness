package com.example.order.orders;

import com.example.order.common.ApiException;
import com.example.order.idempotency.IdempotencyScope;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.orders.OrderDtos.CreateOrderRequest;
import com.example.order.orders.OrderDtos.OrderPage;
import com.example.order.orders.OrderDtos.OrderResponse;
import com.example.order.orders.OrderDtos.PayRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

import static com.example.order.common.Validations.notNull;
import static com.example.order.common.Validations.require;
import static com.example.order.common.Validations.text;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final String USER_HEADER = "X-User-Id";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final OrderService service;
    private final OrderQueryService queryService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public OrderController(OrderService service, OrderQueryService queryService,
                           IdempotencyService idempotency, ObjectMapper objectMapper) {
        this.service = service;
        this.queryService = queryService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(value = USER_HEADER, required = false) String userId,
                                         @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                         @RequestBody CreateOrderRequest request) {
        text(userId, 50, USER_HEADER);
        validateIdempotencyKey(key);
        OrderService.validateCreate(request);
        String fingerprint = IdempotencyService.fingerprint(userId, "POST /api/orders", toJson(request));
        return idempotency.execute(IdempotencyScope.CREATE_ORDER, key, fingerprint, () -> {
            OrderResponse created = service.create(userId, request);
            return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
        });
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) {
        return service.get(id);
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
                throw ApiException.validation("status must be one of the defined order statuses");
            }
        }
        int pageSize = 20;
        if (size != null) {
            try {
                pageSize = Integer.parseInt(size.trim());
            } catch (NumberFormatException e) {
                throw ApiException.validation("size must be an integer between 1 and 100");
            }
            require(pageSize >= 1 && pageSize <= 100, "size must be an integer between 1 and 100");
        }
        return queryService.list(userId, statusFilter, pageSize, cursor);
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<String> pay(@PathVariable long id,
                                      @RequestHeader(value = USER_HEADER, required = false) String userId,
                                      @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                      @RequestBody PayRequest request) {
        validateIdempotencyKey(key);
        notNull(request, "body");
        notNull(request.cardToken(), "cardToken");
        require(!request.cardToken().isBlank(), "cardToken must not be blank");
        String fingerprint = IdempotencyService.fingerprint(userId, "POST /api/orders/" + id + "/pay", toJson(request));
        return idempotency.execute(IdempotencyScope.PAY_ORDER, key, fingerprint,
                () -> ResponseEntity.ok(service.pay(id, key, request.cardToken())));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable long id) {
        return service.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable long id) {
        return service.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable long id) {
        return service.deliver(id);
    }

    private static void validateIdempotencyKey(String key) {
        notNull(key, IDEMPOTENCY_HEADER);
        require(!key.isEmpty() && key.length() <= 64, IDEMPOTENCY_HEADER + " must be 1 to 64 characters");
    }

    /** 요청 본문을 정규화한 JSON. 필드 순서·공백 차이는 같은 요청으로 본다. */
    private String toJson(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
