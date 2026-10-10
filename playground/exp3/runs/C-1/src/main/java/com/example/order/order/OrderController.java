package com.example.order.order;

import com.example.order.common.FieldErrorDetail;
import com.example.order.common.Problems;
import com.example.order.payment.PayRequest;
import com.example.order.payment.PaymentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
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

    static final String USER_HEADER = "X-User-Id";
    static final String KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern KEY_PATTERN = Pattern.compile("^[\\x21-\\x7E]{1,128}$");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderCommandService commands;
    private final OrderQueryService queries;
    private final PaymentService payments;

    public OrderController(OrderCommandService commands, OrderQueryService queries, PaymentService payments) {
        this.commands = commands;
        this.queries = queries;
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(value = USER_HEADER, required = false) String userId,
            @RequestHeader(value = KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        if (userId == null) {
            throw Problems.missingHeader(USER_HEADER);
        }
        if (idempotencyKey == null) {
            throw Problems.missingHeader(KEY_HEADER);
        }
        validateUserId(userId);
        validateKey(idempotencyKey);
        validateNoDuplicateProducts(request);

        OrderCommandService.CreateOutcome outcome = commands.create(userId, idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(URI.create("/api/orders/" + outcome.order().id()));
        if (outcome.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(outcome.order());
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) {
        return queries.get(id);
    }

    @GetMapping
    public OrderPage list(@RequestParam(value = "userId", required = false) String userId,
                          @RequestParam(value = "status", required = false) String status,
                          @RequestParam(value = "size", required = false) String size,
                          @RequestParam(value = "cursor", required = false) String cursor) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (userId != null && userId.isBlank()) {
            errors.add(new FieldErrorDetail("userId", "must not be blank"));
        }
        OrderStatus statusFilter = null;
        if (status != null) {
            try {
                statusFilter = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                errors.add(new FieldErrorDetail("status", "must be one of " + List.of(OrderStatus.values())));
            }
        }
        int pageSize = DEFAULT_PAGE_SIZE;
        if (size != null) {
            try {
                pageSize = Integer.parseInt(size);
                if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                    errors.add(new FieldErrorDetail("size", "must be between 1 and " + MAX_PAGE_SIZE));
                }
            } catch (NumberFormatException e) {
                errors.add(new FieldErrorDetail("size", "must be an integer between 1 and " + MAX_PAGE_SIZE));
            }
        }
        if (!errors.isEmpty()) {
            throw Problems.validationFailed(errors);
        }
        Long beforeId = cursor == null ? null : Cursor.decode(cursor);
        return queries.list(userId, statusFilter, pageSize, beforeId);
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<OrderResponse> pay(@PathVariable long id,
                                             @RequestHeader(value = KEY_HEADER, required = false) String idempotencyKey,
                                             @Valid @RequestBody PayRequest request) {
        if (idempotencyKey == null) {
            throw Problems.missingHeader(KEY_HEADER);
        }
        validateKey(idempotencyKey);
        PaymentService.PayOutcome outcome = payments.pay(id, idempotencyKey, request.cardToken());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.OK);
        if (outcome.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(outcome.order());
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable long id) {
        return commands.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable long id) {
        return commands.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable long id) {
        return commands.deliver(id);
    }

    private static void validateUserId(String userId) {
        if (userId.isBlank() || userId.length() > 64) {
            throw Problems.validationFailed(USER_HEADER, "must be 1..64 characters and not blank");
        }
    }

    private static void validateKey(String key) {
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw Problems.validationFailed(KEY_HEADER, "must be 1..128 printable ASCII characters without spaces");
        }
    }

    private static void validateNoDuplicateProducts(CreateOrderRequest request) {
        Set<Long> seen = new HashSet<>();
        for (CreateOrderRequest.Item item : request.items()) {
            if (!seen.add(item.productId())) {
                throw Problems.validationFailed("items", "productId " + item.productId() + " appears more than once");
            }
        }
    }
}
