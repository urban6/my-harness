package com.example.order.order;

import com.example.order.common.FieldViolation;
import com.example.order.common.Problems;
import com.example.order.common.RequestHeaders;
import com.example.order.order.OrderDtos.CreateOrderRequest;
import com.example.order.order.OrderDtos.OrderPage;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.order.OrderDtos.OrderResult;
import com.example.order.order.OrderDtos.PayRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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

    private static final String REPLAYED = "Idempotent-Replayed";

    private final OrderCreateService createService;
    private final OrderQueryService queryService;
    private final OrderPaymentService paymentService;
    private final OrderFulfillmentService fulfillmentService;

    public OrderController(OrderCreateService createService, OrderQueryService queryService,
            OrderPaymentService paymentService, OrderFulfillmentService fulfillmentService) {
        this.createService = createService;
        this.queryService = queryService;
        this.paymentService = paymentService;
        this.fulfillmentService = fulfillmentService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(name = RequestHeaders.USER_ID, required = false) String userIdHeader,
            @RequestHeader(name = RequestHeaders.IDEMPOTENCY_KEY, required = false) String keyHeader,
            @Valid @RequestBody CreateOrderRequest request) {
        String userId = RequestHeaders.requireUserId(userIdHeader);
        String key = RequestHeaders.requireIdempotencyKey(keyHeader);
        if (request.hasDuplicateProduct()) {
            throw Problems.validation(List.of(new FieldViolation("items", "productId must not be duplicated")));
        }
        OrderResult result = createService.create(userId, key, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}")
                .buildAndExpand(result.order().id()).toUri();
        ResponseEntity.BodyBuilder response = ResponseEntity.created(location);
        if (result.replayed()) {
            response.header(REPLAYED, "true");
        }
        return response.body(result.order());
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable("id") Long id) {
        return queryService.get(id);
    }

    @GetMapping
    public OrderPage list(@RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return queryService.list(userId, status, size, cursor);
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<OrderResponse> pay(@PathVariable("id") Long id,
            @RequestHeader(name = RequestHeaders.IDEMPOTENCY_KEY, required = false) String keyHeader,
            @Valid @RequestBody PayRequest request) {
        String key = RequestHeaders.requireIdempotencyKey(keyHeader);
        OrderResult result = paymentService.pay(id, key, request.cardToken());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header(REPLAYED, "true");
        }
        return response.body(result.order());
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable("id") Long id) {
        return paymentService.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable("id") Long id) {
        return fulfillmentService.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable("id") Long id) {
        return fulfillmentService.deliver(id);
    }
}
