package com.example.order.ordering;

import com.example.order.common.error.RequestValidationException;
import com.example.order.common.web.RequestHeaders;
import com.example.order.idempotency.IdempotentResponse;
import com.example.order.ordering.dto.CreateOrderRequest;
import com.example.order.ordering.dto.OrderPageResponse;
import com.example.order.ordering.dto.OrderResponse;
import com.example.order.ordering.dto.PayRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderCommandService commandService;

    public OrderController(OrderService orderService, OrderCommandService commandService) {
        this.orderService = orderService;
        this.commandService = commandService;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = RequestHeaders.USER_ID, required = false) String userId,
            @RequestHeader(value = RequestHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        RequestHeaders.requireUserId(userId);
        RequestHeaders.requireIdempotencyKey(idempotencyKey);
        rejectDuplicateProducts(request);
        if (request.couponCode() != null && request.couponCode().isBlank()) {
            throw new RequestValidationException("couponCode", "공백만으로 된 값은 사용할 수 없습니다");
        }
        return toResponse(commandService.create(userId, idempotencyKey, request));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) {
        return orderService.get(id);
    }

    @GetMapping
    public OrderPageResponse list(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String cursor) {
        if (userId != null && (userId.isBlank() || userId.length() > 50)) {
            throw new RequestValidationException("query:userId", "공백이 아닌 1~50자여야 합니다");
        }
        OrderStatus statusFilter = null;
        if (status != null) {
            try {
                statusFilter = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new RequestValidationException("query:status", "정의되지 않은 상태입니다: " + status);
            }
        }
        int pageSize = 20;
        if (size != null) {
            try {
                pageSize = Integer.parseInt(size);
            } catch (NumberFormatException e) {
                throw new RequestValidationException("query:size", "정수여야 합니다");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw new RequestValidationException("query:size", "1~100 이어야 합니다");
            }
        }
        OrderCursor decoded = cursor == null ? null : OrderCursor.decode(cursor);
        return orderService.list(userId, statusFilter, pageSize, decoded);
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<String> pay(
            @PathVariable long id,
            @RequestHeader(value = RequestHeaders.USER_ID, required = false) String userId,
            @RequestHeader(value = RequestHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody PayRequest request) {
        RequestHeaders.requireIdempotencyKey(idempotencyKey);
        return toResponse(commandService.pay(id, idempotencyKey, userId, request));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable long id) {
        return commandService.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable long id) {
        return orderService.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable long id) {
        return orderService.deliver(id);
    }

    private static void rejectDuplicateProducts(CreateOrderRequest request) {
        Set<Long> seen = new HashSet<>();
        for (CreateOrderRequest.Item item : request.items()) {
            if (!seen.add(item.productId())) {
                throw new RequestValidationException("items", "같은 productId를 중복해서 주문할 수 없습니다: " + item.productId());
            }
        }
    }

    /** 최초 응답과 재생 응답을 같은 경로로 내보낸다: 저장된 JSON 원문 그대로. */
    private static ResponseEntity<String> toResponse(IdempotentResponse r) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON);
        if (r.locationPath() != null) {
            URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path(r.locationPath()).build().toUri();
            builder.location(location);
        }
        return builder.body(r.body());
    }
}
