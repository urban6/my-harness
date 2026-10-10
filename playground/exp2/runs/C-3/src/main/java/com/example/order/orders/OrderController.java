package com.example.order.orders;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import com.example.order.idempotency.StoredResponse;
import com.example.order.orders.dto.CreateOrderRequest;
import com.example.order.orders.dto.OrderPageResponse;
import com.example.order.orders.dto.OrderResponse;
import com.example.order.orders.dto.PayRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
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

// 주의: 클래스에 @Validated를 붙이지 않는다 (MVC 내장 메서드 검증 -> HandlerMethodValidationException)
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderFacade orderFacade;
    private final OrderService orderService;

    public OrderController(OrderFacade orderFacade, OrderService orderService) {
        this.orderFacade = orderFacade;
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader("X-User-Id") @NotBlank @Size(max = 50) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        return toResponse(orderFacade.create(userId, idempotencyKey, request));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) {
        return orderService.get(id);
    }

    @GetMapping
    public OrderPageResponse list(
            @RequestParam(required = false) @Size(max = 50) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String cursor) {
        OrderStatus statusFilter = null;
        if (status != null && !status.isEmpty()) {
            try {
                statusFilter = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "정의되지 않은 status 입니다: " + status);
            }
        }
        String userFilter = (userId == null || userId.isEmpty()) ? null : userId;
        String cursorValue = (cursor == null || cursor.isEmpty()) ? null : cursor;
        return orderService.list(userFilter, statusFilter, size, cursorValue);
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<String> pay(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody PayRequest request) {
        return toResponse(orderFacade.pay(id, userId, idempotencyKey, request));
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

    private static ResponseEntity<String> toResponse(StoredResponse stored) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(stored.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (stored.location() != null) {
            builder.header(HttpHeaders.LOCATION, stored.location());
        }
        return builder.body(stored.bodyJson());
    }
}
