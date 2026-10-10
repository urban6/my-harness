package com.example.order.order;

import com.example.order.common.Hashing;
import com.example.order.common.InvalidOrderStateException;
import com.example.order.common.OrderExpiredException;
import com.example.order.common.Problems;
import com.example.order.common.TimeSupport;
import com.example.order.idempotency.IdempotencyKey;
import com.example.order.idempotency.IdempotencyKeyRepository;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.order.OrderDtos.OrderResult;
import com.example.order.payment.Payment;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PaymentGatewayClient.Approval;
import com.example.order.payment.PaymentRepository;
import com.example.order.payment.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pay / cancel (+refund). Both run under the order row lock (FOR UPDATE) and call the PG inside the transaction
 * (D-09). PG failures are exceptions that are NOT in noRollbackFor, so everything (including the idempotency key)
 * rolls back; lazy-expiry exceptions ARE in noRollbackFor so the EXPIRED transition commits.
 */
@Service
public class OrderPaymentService {

    private final PurchaseOrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository idempotencyRepository;
    private final PaymentGatewayClient gateway;
    private final OrderInventory inventory;
    private final OrderExpiryService expiryService;
    private final Clock clock;

    public OrderPaymentService(PurchaseOrderRepository orderRepository, OrderItemRepository itemRepository,
            PaymentRepository paymentRepository, IdempotencyKeyRepository idempotencyRepository,
            PaymentGatewayClient gateway, OrderInventory inventory, OrderExpiryService expiryService, Clock clock) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.paymentRepository = paymentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.gateway = gateway;
        this.inventory = inventory;
        this.expiryService = expiryService;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = { OrderExpiredException.class, InvalidOrderStateException.class })
    public OrderResult pay(long orderId, String idempotencyKey, String cardToken) {
        Instant now = TimeSupport.now(clock);
        PurchaseOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> Problems.orderNotFound(orderId));
        String hash = Hashing.sha256Hex(orderId + "|" + cardToken);

        // 2. replay / conflict check
        IdempotencyKey existing = idempotencyRepository.findByOperationAndScopeKeyAndIdemKey(IdempotencyKey.PAY,
                IdempotencyKey.PAY_SCOPE, idempotencyKey).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, hash, idempotencyKey, order);
        }

        // 3. state check
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            if (order.getStatus() == OrderStatus.EXPIRED) {
                throw new OrderExpiredException(orderId, order.getExpiresAt());
            }
            throw new InvalidOrderStateException(orderId, order.getStatus().name(), "pay");
        }

        // 4. expiry check: commit the EXPIRED transition, then 409
        if (expiryService.expireLockedIfDue(order, now)) {
            throw new OrderExpiredException(orderId, order.getExpiresAt());
        }

        // 5. claim the key (0 rows = used concurrently by another order's payment)
        if (idempotencyRepository.claim(IdempotencyKey.PAY, IdempotencyKey.PAY_SCOPE, idempotencyKey, hash, now) == 0) {
            IdempotencyKey raced = idempotencyRepository.findByOperationAndScopeKeyAndIdemKey(IdempotencyKey.PAY,
                    IdempotencyKey.PAY_SCOPE, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key vanished after conflict"));
            return replayOrConflict(raced, hash, idempotencyKey, order);
        }

        // 6. PG call; PaymentGatewayException (502/504) rolls everything back
        Approval approval = gateway.approve(orderId, order.getTotalPrice(), cardToken, idempotencyKey);

        // 7. apply result
        List<OrderItem> items = itemRepository.findByOrderIdOrderByIdAsc(orderId);
        String outcome;
        if (approval.approved()) {
            order.markPaid(now);
            paymentRepository.save(new Payment(orderId, approval.paymentId(), PaymentStatus.APPROVED,
                    order.getTotalPrice(), now));
            inventory.confirm(order, items);
            outcome = "APPROVED";
        } else {
            order.transitionTo(OrderStatus.PAYMENT_FAILED, now);
            paymentRepository.save(new Payment(orderId, approval.paymentId(), PaymentStatus.DECLINED,
                    order.getTotalPrice(), now));
            inventory.release(order, items);
            outcome = "DECLINED";
        }
        idempotencyRepository.complete(IdempotencyKey.PAY, IdempotencyKey.PAY_SCOPE, idempotencyKey, orderId, outcome);
        return new OrderResult(OrderResponse.of(order, items), false);
    }

    @Transactional(noRollbackFor = { OrderExpiredException.class, InvalidOrderStateException.class })
    public OrderResponse cancel(long orderId) {
        Instant now = TimeSupport.now(clock);
        PurchaseOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> Problems.orderNotFound(orderId));
        expiryService.expireLockedIfDue(order, now);
        List<OrderItem> items = itemRepository.findByOrderIdOrderByIdAsc(orderId);

        switch (order.getStatus()) {
            case PENDING_PAYMENT -> {
                inventory.release(order, items);
                order.transitionTo(OrderStatus.CANCELLED, now);
            }
            case PAID -> {
                Payment payment = paymentRepository.findByOrderId(orderId)
                        .orElseThrow(() -> new IllegalStateException("PAID order " + orderId + " has no payment"));
                gateway.refund(payment.getPaymentId()); // 502/504 -> rollback, order stays PAID (D-06)
                payment.markRefunded(now);
                order.transitionTo(OrderStatus.REFUNDED, now);
                inventory.restoreStock(order, items);
            }
            default -> throw new InvalidOrderStateException(orderId, order.getStatus().name(), "cancel");
        }
        return OrderResponse.of(order, items);
    }

    private OrderResult replayOrConflict(IdempotencyKey existing, String hash, String key, PurchaseOrder lockedOrder) {
        if (!existing.getRequestHash().equals(hash) || existing.getOrderId() == null
                || !existing.getOrderId().equals(lockedOrder.getId())) {
            throw Problems.idempotencyKeyReused(key);
        }
        return new OrderResult(OrderResponse.of(lockedOrder,
                itemRepository.findByOrderIdOrderByIdAsc(lockedOrder.getId())), true);
    }
}
