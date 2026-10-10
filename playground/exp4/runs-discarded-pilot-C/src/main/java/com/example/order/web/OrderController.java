package com.example.order.web;

import com.example.order.domain.OrderStatus;
import com.example.order.service.CursorCodec;
import com.example.order.service.ExpiryService;
import com.example.order.service.OrderCreationService;
import com.example.order.service.OrderLifecycleService;
import com.example.order.service.OrderQueryService;
import com.example.order.service.PaymentService;
import com.example.order.service.StoredResponse;
import com.example.order.web.dto.OrderCreateRequest;
import com.example.order.web.dto.OrderPageResponse;
import com.example.order.web.dto.OrderResponse;
import com.example.order.web.dto.PayRequest;
import com.example.order.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private static final int DEFAULT_SIZE = 20;

    private final OrderCreationService creation;
    private final PaymentService payment;
    private final OrderLifecycleService lifecycle;
    private final OrderQueryService query;
    private final ExpiryService expiry;

    public OrderController(OrderCreationService creation, PaymentService payment, OrderLifecycleService lifecycle,
                           OrderQueryService query, ExpiryService expiry) {
        this.creation = creation;
        this.payment = payment;
        this.lifecycle = lifecycle;
        this.query = query;
        this.expiry = expiry;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<String> create(
            @RequestHeader(name = "X-User-Id", required = false) String userId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest body) {
        String user = HeaderValidator.userId(userId);
        String key = HeaderValidator.idempotencyKey(idempotencyKey);
        return toResponse(creation.create(user, key, body));
    }

    @GetMapping("/api/orders/{id}")
    public OrderResponse get(@PathVariable long id) {
        expiry.expireDue();
        return query.get(id);
    }

    @GetMapping("/api/orders")
    public OrderPageResponse list(HttpServletRequest request) {
        String userId = single(request, "userId");
        if (userId != null && userId.isBlank()) {
            throw ApiException.validation("userId: must not be blank");
        }
        String statusRaw = single(request, "status");
        OrderStatus status = null;
        if (statusRaw != null) {
            try {
                status = OrderStatus.valueOf(statusRaw);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("status: unknown order status");
            }
        }
        String sizeRaw = single(request, "size");
        int size = DEFAULT_SIZE;
        if (sizeRaw != null) {
            try {
                size = Integer.parseInt(sizeRaw);
            } catch (NumberFormatException e) {
                throw ApiException.validation("size: must be an integer between 1 and 100");
            }
            if (size < 1 || size > 100) {
                throw ApiException.validation("size: must be between 1 and 100");
            }
        }
        String cursorRaw = single(request, "cursor");
        CursorCodec.Cursor cursor = cursorRaw == null ? null : CursorCodec.decode(cursorRaw);
        expiry.expireDue();
        return query.list(new OrderQueryService.ListQuery(userId, status, size, cursor));
    }

    @PostMapping("/api/orders/{id}/pay")
    public ResponseEntity<String> pay(
            @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-User-Id", required = false) String userId,
            @Valid @RequestBody PayRequest body) {
        String key = HeaderValidator.idempotencyKey(idempotencyKey);
        return toResponse(payment.pay(id, key, userId, body.cardToken()));
    }

    @PostMapping("/api/orders/{id}/cancel")
    public OrderResponse cancel(@PathVariable long id) {
        return lifecycle.cancel(id);
    }

    @PostMapping("/api/orders/{id}/ship")
    public OrderResponse ship(@PathVariable long id) {
        return lifecycle.ship(id);
    }

    @PostMapping("/api/orders/{id}/deliver")
    public OrderResponse deliver(@PathVariable long id) {
        return lifecycle.deliver(id);
    }

    /** 최초 응답도 재생도 저장된 JSON 문자열을 그대로 내보낸다 (바이트 동일). */
    private static ResponseEntity<String> toResponse(StoredResponse stored) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(stored.status())
                .contentType(MediaType.parseMediaType(stored.contentType()));
        if (stored.location() != null) {
            builder.header(HttpHeaders.LOCATION, stored.location());
        }
        return builder.body(stored.body());
    }

    /** 쿼리 파라미터: 없으면 null, 빈 값이거나 여러 번 주어지면 400. */
    private static String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            return null;
        }
        if (values.length != 1 || values[0].isEmpty()) {
            throw ApiException.validation(name + ": must be provided exactly once with a non-empty value");
        }
        return values[0];
    }
}
