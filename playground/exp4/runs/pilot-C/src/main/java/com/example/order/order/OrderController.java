package com.example.order.order;

import com.example.order.common.ApiResult;
import com.example.order.idempotency.IdempotencyFacade;
import com.example.order.idempotency.IdempotencyScope;
import com.example.order.payment.OrderPaymentService;
import com.example.order.payment.PayRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    private final IdempotencyFacade idempotency;
    private final OrderCreationService creationService;
    private final OrderQueryService queryService;
    private final OrderPaymentService paymentService;
    private final OrderTransitionService transitionService;

    public OrderController(IdempotencyFacade idempotency, OrderCreationService creationService,
                           OrderQueryService queryService, OrderPaymentService paymentService,
                           OrderTransitionService transitionService) {
        this.idempotency = idempotency;
        this.creationService = creationService;
        this.queryService = queryService;
        this.paymentService = paymentService;
        this.transitionService = transitionService;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader("X-User-Id") @NotBlank @Size(max = 50) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest request) {
        ApiResult result = idempotency.execute(IdempotencyScope.ORDER_CREATE, idempotencyKey, userId,
                "/api/orders", request.normalized(),
                recordId -> creationService.create(userId, request, recordId));
        return toResponse(result);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return queryService.get(id);
    }

    @GetMapping
    public OrderPage list(@RequestParam(required = false) String userId,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) Integer size,
                          @RequestParam(required = false) String cursor) {
        return queryService.list(userId, status, size, cursor);
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<String> pay(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = false)
            @Size(min = 1, max = 50) @Pattern(regexp = "(?s).*\\S.*") String userId,
            @Valid @RequestBody PayRequest request) {
        ApiResult result = idempotency.execute(IdempotencyScope.ORDER_PAY, idempotencyKey, userId,
                "/api/orders/" + id + "/pay", "cardToken=" + request.cardToken(),
                recordId -> paymentService.pay(id, request.cardToken(), idempotencyKey, recordId));
        return toResponse(result);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id) {
        return paymentService.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable Long id) {
        return transitionService.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable Long id) {
        return transitionService.deliver(id);
    }

    private static ResponseEntity<String> toResponse(ApiResult result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (result.location() != null) {
            builder.location(ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path(result.location()).build().toUri());
        }
        return builder.body(result.body());
    }
}
