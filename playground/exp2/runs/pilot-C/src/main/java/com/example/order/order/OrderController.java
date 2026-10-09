package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.idempotency.IdemResult;
import com.example.order.idempotency.IdempotencyScope;
import com.example.order.idempotency.IdempotentExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 컨트롤러/파사드에는 @Transactional 을 두지 않는다 (멱등 트랜잭션과 업무 트랜잭션 비중첩). */
@RestController
public class OrderController {
    private final OrderService orderService;
    private final OrderQueryService queryService;
    private final PaymentFacade paymentFacade;
    private final IdempotentExecutor idempotent;

    public OrderController(OrderService orderService, OrderQueryService queryService, PaymentFacade paymentFacade,
                           IdempotentExecutor idempotent) {
        this.orderService = orderService;
        this.queryService = queryService;
        this.paymentFacade = paymentFacade;
        this.idempotent = idempotent;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<Object> create(
            @RequestHeader("X-User-Id") @NotBlank @Size(max = 50) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String key,
            @Valid @RequestBody CreateOrderRequest req) {
        req.validateCross();
        String fp = idempotent.fingerprint(IdempotencyScope.CREATE_ORDER, userId, "/api/orders", req);
        return idempotent.execute(IdempotencyScope.CREATE_ORDER, key, fp, () -> {
            OrderResponse res = orderService.create(userId, req);
            return new IdemResult(201, res, "/api/orders/" + res.id());
        });
    }

    @GetMapping("/api/orders/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return queryService.get(id);
    }

    @GetMapping("/api/orders")
    public OrderPageResponse list(
            @RequestParam(name = "userId", required = false)
            @Size(min = 1, max = 50) @Pattern(regexp = "(?s).*\\S.*") String userId,
            @RequestParam(name = "status", required = false) OrderStatus status,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) Integer size,
            @RequestParam(name = "cursor", required = false) String cursor) {
        OrderCursor decoded = (cursor == null || cursor.isEmpty()) ? null : OrderCursor.decode(cursor);
        return queryService.list(userId, status, size, decoded);
    }

    @PostMapping("/api/orders/{id}/pay")
    public ResponseEntity<Object> pay(
            @PathVariable Long id,
            @RequestHeader(name = "X-User-Id", required = false) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String key,
            @Valid @RequestBody PayRequest req) {
        String fp = idempotent.fingerprint(IdempotencyScope.PAY_ORDER, userId, "/api/orders/" + id + "/pay", req);
        return idempotent.execute(IdempotencyScope.PAY_ORDER, key, fp, () ->
                new IdemResult(200, paymentFacade.pay(id, key, req.cardToken()), null));
    }

    @PostMapping("/api/orders/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id) {
        return orderService.cancel(id);
    }

    @PostMapping("/api/orders/{id}/ship")
    public OrderResponse ship(@PathVariable Long id) {
        return orderService.ship(id);
    }

    @PostMapping("/api/orders/{id}/deliver")
    public OrderResponse deliver(@PathVariable Long id) {
        return orderService.deliver(id);
    }
}
