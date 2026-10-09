package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.order.OrderDtos.CreateOrderRequest;
import com.example.order.order.OrderDtos.OrderItemRequest;
import com.example.order.order.OrderDtos.OrderPage;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.order.OrderDtos.PayRequest;
import com.example.order.payment.PaymentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
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

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final IdempotencyService idempotency;

    public OrderController(OrderService orderService, PaymentService paymentService, IdempotencyService idempotency) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "X-User-Id", required = false) String userId,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @Valid @RequestBody CreateOrderRequest request) {
        // C3: 모든 400 검사를 멱등 키 처리보다 먼저 한다.
        validateUserId(userId);
        validateIdempotencyKey(idempotencyKey);
        validateItems(request);

        Fingerprint fingerprint = new Fingerprint(userId, "/api/orders", request);
        return idempotency.execute(IdempotencyService.SCOPE_CREATE_ORDER, idempotencyKey, fingerprint, () -> {
            OrderResponse created = orderService.create(userId, request);
            return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
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
        OrderStatus statusFilter = status == null ? null : parseStatus(status);
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : parseSize(size);
        OrderCursor after = cursor == null ? null : OrderCursor.decode(cursor);
        return orderService.list(userId, statusFilter, pageSize, after);
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<?> pay(@PathVariable long id,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                 @RequestBody PayRequest request) {
        validateIdempotencyKey(idempotencyKey);
        if (request.cardToken() == null || request.cardToken().isBlank()) {
            throw ApiException.validation("cardToken: must not be blank");
        }

        Fingerprint fingerprint = new Fingerprint(userId, "/api/orders/" + id + "/pay", request);
        return idempotency.execute(IdempotencyService.SCOPE_PAY_ORDER, idempotencyKey, fingerprint,
                () -> ResponseEntity.ok(paymentService.pay(id, request.cardToken(), idempotencyKey)));
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
            throw ApiException.validation("X-User-Id header must be 1 to 50 non-blank characters");
        }
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 64) {
            throw ApiException.validation("Idempotency-Key header must be 1 to 64 characters");
        }
    }

    private static void validateItems(CreateOrderRequest request) {
        Set<Long> productIds = new HashSet<>();
        for (OrderItemRequest item : request.items()) {
            if (!productIds.add(item.productId())) {
                throw ApiException.validation("items: duplicate productId " + item.productId());
            }
        }
        if (request.couponCode() != null && request.couponCode().isBlank()) {
            throw ApiException.validation("couponCode: must not be blank when present");
        }
    }

    private static OrderStatus parseStatus(String status) {
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("status: unknown value " + status);
        }
    }

    private static int parseSize(String size) {
        int value;
        try {
            value = Integer.parseInt(size);
        } catch (NumberFormatException e) {
            throw ApiException.validation("size: must be an integer");
        }
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw ApiException.validation("size: must be between 1 and " + MAX_PAGE_SIZE);
        }
        return value;
    }

    /** 멱등 키 비교용 요청 지문: 같은 사용자·경로·본문이면 같은 요청이다. */
    private record Fingerprint(String userId, String path, Object body) {
    }
}
