package com.example.order.order;

import com.example.order.common.Hashing;
import com.example.order.common.Problems;
import com.example.order.payment.PaymentService;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Non-transactional orchestration: order creation idempotency loop and cancel dispatch. */
@Service
public class OrderCommandService {

    private static final int MAX_CREATE_ATTEMPTS = 3;

    public record CreateOutcome(OrderResponse order, boolean replayed) {
    }

    private final OrderRepository orders;
    private final OrderCreator creator;
    private final OrderAssembler assembler;
    private final OrderTransitionService transitions;
    private final PaymentService payments;

    public OrderCommandService(OrderRepository orders, OrderCreator creator, OrderAssembler assembler,
                               OrderTransitionService transitions, PaymentService payments) {
        this.orders = orders;
        this.creator = creator;
        this.assembler = assembler;
        this.transitions = transitions;
        this.payments = payments;
    }

    public CreateOutcome create(String userId, String idempotencyKey, CreateOrderRequest request) {
        String hash = requestHash(request);
        for (int attempt = 0; attempt < MAX_CREATE_ATTEMPTS; attempt++) {
            Optional<OrderRow> existing = orders.findByUserAndKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                if (!hash.equals(existing.get().requestHash())) {
                    throw Problems.idempotencyKeyConflict(
                            "The Idempotency-Key was already used with a different request body");
                }
                return new CreateOutcome(assembler.assemble(existing.get()), true);
            }
            Optional<Long> created = creator.create(userId, idempotencyKey, hash, request);
            if (created.isPresent()) {
                return new CreateOutcome(assembler.load(created.get()), false);
            }
            // a concurrent request with the same key committed first: loop to replay / conflict
        }
        throw new IllegalStateException("Could not resolve idempotent order creation");
    }

    public OrderResponse cancel(long orderId) {
        OrderRow current = orders.findById(orderId).orElseThrow(() -> Problems.orderNotFound(orderId));
        if (current.status() == OrderStatus.PAID) {
            return payments.refund(orderId);
        }
        return transitions.cancelPending(orderId); // non-pending statuses are mapped to 409 inside
    }

    public OrderResponse ship(long orderId) {
        return transitions.ship(orderId);
    }

    public OrderResponse deliver(long orderId) {
        return transitions.deliver(orderId);
    }

    /** SHA-256 of "items=<productId>:<qty>,...(productId asc);coupon=<code or empty>". */
    static String requestHash(CreateOrderRequest request) {
        String items = request.items().stream()
                .sorted(Comparator.comparingLong(CreateOrderRequest.Item::productId))
                .map(i -> i.productId() + ":" + i.quantity())
                .collect(Collectors.joining(","));
        return Hashing.sha256Hex("items=" + items + ";coupon=" + (request.couponCode() == null ? "" : request.couponCode()));
    }
}
