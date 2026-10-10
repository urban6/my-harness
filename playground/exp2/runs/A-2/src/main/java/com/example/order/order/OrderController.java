package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.PathIds;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.idempotency.IdempotencyService.Scope;
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

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record CreateOrderRequest(List<Item> items, String couponCode) {
        public record Item(Long productId, Long quantity) {
        }
    }

    public record PayRequest(String cardToken) {
    }

    private final OrderService orderService;
    private final IdempotencyService idempotency;

    public OrderController(OrderService orderService, IdempotencyService idempotency) {
        this.orderService = orderService;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "X-User-Id", required = false) String userId,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestBody CreateOrderRequest req,
                                    HttpServletRequest http) {
        if (userId == null || userId.isBlank() || userId.length() > 50) {
            throw ApiException.validation("X-User-Id header must be 1-50 characters and not blank");
        }
        validateIdempotencyKey(idempotencyKey);
        List<OrderService.LineRequest> lines = validateItems(req.items());

        String fingerprint = idempotency.fingerprint(userId, http.getRequestURI(), req);
        return idempotency.execute(Scope.CREATE_ORDER, idempotencyKey, fingerprint, () -> {
            OrderResponse order = orderService.create(userId, lines, req.couponCode());
            return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
        });
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable String id) {
        return orderService.get(orderId(id));
    }

    @GetMapping
    public OrderService.Page list(@RequestParam(required = false) String userId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String size,
                                  @RequestParam(required = false) String cursor) {
        OrderStatus statusFilter = null;
        if (status != null) {
            try {
                statusFilter = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("Unknown status: " + status);
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
        OrderCursor decoded = cursor == null ? null : OrderCursor.decode(cursor);
        return orderService.list(userId, statusFilter, pageSize, decoded);
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<?> pay(@PathVariable String id,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                 @RequestBody PayRequest req,
                                 HttpServletRequest http) {
        validateIdempotencyKey(idempotencyKey);
        if (req.cardToken() == null || req.cardToken().isBlank()) {
            throw ApiException.validation("cardToken must not be blank");
        }
        String fingerprint = idempotency.fingerprint(userId, http.getRequestURI(), req);
        return idempotency.execute(Scope.PAY_ORDER, idempotencyKey, fingerprint,
                () -> ResponseEntity.ok(orderService.pay(orderId(id), idempotencyKey, req.cardToken())));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable String id) {
        return orderService.cancel(orderId(id));
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable String id) {
        return orderService.ship(orderId(id));
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable String id) {
        return orderService.deliver(orderId(id));
    }

    private static long orderId(String raw) {
        return PathIds.parse(raw, "ORDER_NOT_FOUND", "Order");
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 64) {
            throw ApiException.validation("Idempotency-Key header must be 1-64 characters");
        }
    }

    private static List<OrderService.LineRequest> validateItems(List<CreateOrderRequest.Item> items) {
        if (items == null || items.isEmpty() || items.size() > 20) {
            throw ApiException.validation("items must contain 1-20 entries");
        }
        Set<Long> seen = new HashSet<>();
        for (CreateOrderRequest.Item item : items) {
            if (item == null || item.productId() == null) {
                throw ApiException.validation("items[].productId is required");
            }
            if (item.quantity() == null || item.quantity() < 1 || item.quantity() > 1000) {
                throw ApiException.validation("items[].quantity must be between 1 and 1000");
            }
            if (!seen.add(item.productId())) {
                throw ApiException.validation("items must not contain duplicate productId " + item.productId());
            }
        }
        return items.stream()
                .map(i -> new OrderService.LineRequest(i.productId(), i.quantity().intValue()))
                .toList();
    }
}
