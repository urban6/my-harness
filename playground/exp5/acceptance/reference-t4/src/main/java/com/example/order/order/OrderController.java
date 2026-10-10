package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.TenantFilter;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.idempotency.IdempotencyService.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public OrderController(OrderService service, IdempotencyService idempotency, ObjectMapper mapper) {
        this.service = service;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    public record ItemRequest(@NotNull Long productId, @NotNull @Min(1) @Max(1000) Long quantity) {
    }

    public record CreateOrderRequest(@NotNull @Size(min = 1, max = 20) List<@NotNull @Valid ItemRequest> items,
            String couponCode) {
    }

    public record PayRequest(@NotBlank String cardToken) {
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestAttribute(TenantFilter.ATTRIBUTE) String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @Valid @RequestBody CreateOrderRequest req) {
        // 400
        if (userId == null || userId.isBlank() || userId.length() > 50) {
            throw ApiException.validation("X-User-Id header is required (1-50 non-blank characters)");
        }
        validateIdempotencyKey(idemKey);
        Set<Long> seen = new HashSet<>();
        for (ItemRequest item : req.items()) {
            if (!seen.add(item.productId())) {
                throw ApiException.validation("duplicate productId " + item.productId());
            }
        }
        List<OrderService.ItemCommand> items = req.items().stream()
                .map(i -> new OrderService.ItemCommand(i.productId(), i.quantity())).toList();

        // same request = same X-User-Id + same body
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("userId", userId);
        canonical.put("items", items.stream().map(i -> List.of(i.productId(), i.quantity())).toList());
        canonical.put("couponCode", req.couponCode());
        String fp = IdempotencyService.fingerprint(toJson(canonical));

        return idempotent(tenantId, IdempotencyService.SCOPE_CREATE_ORDER, idemKey, fp, () -> {
            OrderService.Written w = service.create(tenantId, userId, items, req.couponCode(), idemKey);
            return ResponseEntity.created(URI.create(OrderService.location(w.orderId())))
                    .contentType(MediaType.APPLICATION_JSON).body(w.body());
        });
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<String> pay(@RequestAttribute(TenantFilter.ATTRIBUTE) String tenantId,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @Valid @RequestBody PayRequest req) {
        validateIdempotencyKey(idemKey);
        // same request = same path + same body
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("orderId", id);
        canonical.put("cardToken", req.cardToken());
        String fp = IdempotencyService.fingerprint(toJson(canonical));

        return idempotent(tenantId, IdempotencyService.SCOPE_PAY, idemKey, fp, () -> {
            OrderService.Written w = service.pay(tenantId, id, req.cardToken(), idemKey);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(w.body());
        });
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<String> cancel(@RequestAttribute(TenantFilter.ATTRIBUTE) String tenantId,
            @PathVariable long id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.cancel(tenantId, id));
    }

    @PostMapping("/{id}/ship")
    public ResponseEntity<String> ship(@RequestAttribute(TenantFilter.ATTRIBUTE) String tenantId,
            @PathVariable long id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.ship(tenantId, id));
    }

    @PostMapping("/{id}/deliver")
    public ResponseEntity<String> deliver(@RequestAttribute(TenantFilter.ATTRIBUTE) String tenantId,
            @PathVariable long id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.deliver(tenantId, id));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@RequestAttribute(TenantFilter.ATTRIBUTE) String tenantId, @PathVariable long id) {
        return service.get(tenantId, id);
    }

    @GetMapping
    public OrderService.Page list(@RequestAttribute(TenantFilter.ATTRIBUTE) String tenantId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String cursor) {
        OrderStatus statusFilter = null;
        if (status != null) {
            try {
                statusFilter = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("unknown status " + status);
            }
        }
        int pageSize = 20;
        if (size != null) {
            try {
                pageSize = Integer.parseInt(size.trim());
            } catch (NumberFormatException e) {
                throw ApiException.validation("size must be an integer between 1 and 100");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw ApiException.validation("size must be between 1 and 100");
            }
        }
        OrderCursor decoded = (cursor == null || cursor.isEmpty()) ? null : OrderCursor.decode(cursor);
        String userFilter = (userId == null || userId.isEmpty()) ? null : userId;
        return service.list(tenantId, userFilter, statusFilter, pageSize, decoded);
    }

    private ResponseEntity<String> idempotent(String tenantId, String scope, String key, String fingerprint,
            Supplier<ResponseEntity<String>> action) {
        Optional<StoredResponse> stored = idempotency.begin(tenantId, scope, key, fingerprint);
        if (stored.isPresent()) {
            StoredResponse r = stored.get();
            ResponseEntity.BodyBuilder b = ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON);
            if (r.location() != null) {
                b.location(URI.create(r.location()));
            }
            return b.body(r.body());
        }
        try {
            return action.get();
        } catch (RuntimeException | Error e) {
            idempotency.release(tenantId, scope, key);
            throw e;
        }
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 64) {
            throw ApiException.validation("Idempotency-Key header is required (1-64 characters)");
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
