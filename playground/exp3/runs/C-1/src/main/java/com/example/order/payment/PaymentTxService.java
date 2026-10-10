package com.example.order.payment;

import com.example.order.common.ApiException;
import com.example.order.common.Problems;
import com.example.order.common.Times;
import com.example.order.config.OrderPaymentProperties;
import com.example.order.order.OrderAction;
import com.example.order.order.OrderAssembler;
import com.example.order.order.OrderRepository;
import com.example.order.order.OrderResponse;
import com.example.order.order.OrderRow;
import com.example.order.order.OrderStateMachine;
import com.example.order.order.OrderStatus;
import com.example.order.order.OrderTransitionService;
import com.example.order.order.ReservationReleaser;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short DB transactions around a PG call (TX-A before, TX-C after). Deliberately a separate bean from
 * PaymentService so the @Transactional proxies apply and the PG call itself stays outside any transaction.
 */
@Service
public class PaymentTxService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTxService.class);

    public record PayBegin(boolean replay, OrderResponse replayedOrder, UUID token, long amount) {
    }

    public record RefundBegin(UUID token, String pgPaymentId) {
    }

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final OrderAssembler assembler;
    private final OrderTransitionService transitions;
    private final ReservationReleaser releaser;
    private final OrderPaymentProperties props;
    private final Clock clock;

    public PaymentTxService(OrderRepository orders, PaymentRepository payments, OrderAssembler assembler,
                            OrderTransitionService transitions, ReservationReleaser releaser,
                            OrderPaymentProperties props, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.assembler = assembler;
        this.transitions = transitions;
        this.releaser = releaser;
        this.props = props;
        this.clock = clock;
    }

    /** TX-A for pay: replay/conflict/state decision, lazy expiry, lease claim, payment row upsert. */
    @Transactional(noRollbackFor = ApiException.class) // keep the lazy expiry when answering order-expired
    public PayBegin beginPayment(long orderId, String idempotencyKey, String cardHash) {
        Instant now = Times.now(clock);
        OrderRow order = loadOrder(orderId);
        PaymentRow payment = payments.findByOrderId(orderId).orElse(null);

        if (payment != null) {
            if (payment.idempotencyKey().equals(idempotencyKey)) {
                if (!payment.requestHash().equals(cardHash)) {
                    throw Problems.idempotencyKeyConflict(
                            "The Idempotency-Key was already used with a different cardToken");
                }
                if (payment.status().isFinalDecision()) {
                    return new PayBegin(true, assembler.assemble(order), null, 0);
                }
            } else {
                if (order.status() == OrderStatus.PENDING_PAYMENT) {
                    throw Problems.idempotencyKeyConflict(
                            "This order already has a payment attempt bound to a different Idempotency-Key");
                }
                throw OrderStateMachine.failureFor(order, OrderAction.PAY, now);
            }
        }

        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            throw OrderStateMachine.failureFor(order, OrderAction.PAY, now);
        }
        if (!order.expiresAt().isAfter(now)) {
            if (!order.leaseActive(now)) {
                transitions.expireIfDue(orderId);
            }
            throw OrderStateMachine.failureFor(loadOrder(orderId), OrderAction.PAY, now);
        }

        UUID token = UUID.randomUUID();
        if (orders.claimPayLease(orderId, token, now, now.plus(props.leaseDuration())) != 1) {
            throw OrderStateMachine.failureFor(loadOrder(orderId), OrderAction.PAY, now);
        }
        payments.upsertInitiated(orderId, idempotencyKey, cardHash, order.totalPrice(), now);
        return new PayBegin(false, null, token, order.totalPrice());
    }

    /** TX-C for pay: APPROVED -> PAID, DECLINED -> PAYMENT_FAILED (+ stock/coupon give-back). */
    @Transactional
    public OrderResponse completePayment(long orderId, UUID token, GatewayResult result) {
        Instant now = Times.now(clock);
        OrderStatus target = result.approved() ? OrderStatus.PAID : OrderStatus.PAYMENT_FAILED;
        if (orders.completePayment(orderId, target, token, now) != 1) {
            log.error("Payment completion lost the lease for order {} (PG result {}); manual reconciliation needed",
                    orderId, target);
            throw Problems.operationInProgress("PAY");
        }
        if (result.approved()) {
            payments.markApproved(orderId, result.paymentId(), now);
        } else {
            releaser.release(orderId, now);
            payments.markDeclined(orderId, now);
        }
        return assembler.load(orderId);
    }

    /** TX-C for a gateway failure: only drop the lease, keep the order PENDING_PAYMENT / PAID. */
    @Transactional
    public void abortLease(long orderId, UUID token, String error) {
        Instant now = Times.now(clock);
        orders.releaseLease(orderId, token, now);
        payments.recordError(orderId, error, now);
    }

    /** TX-A for refund (cancel on PAID). */
    @Transactional
    public RefundBegin beginRefund(long orderId) {
        Instant now = Times.now(clock);
        UUID token = UUID.randomUUID();
        if (orders.claimRefundLease(orderId, token, now, now.plus(props.leaseDuration())) != 1) {
            throw OrderStateMachine.failureFor(loadOrder(orderId), OrderAction.CANCEL, now);
        }
        PaymentRow payment = payments.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("PAID order without payment row: " + orderId));
        if (payment.pgPaymentId() == null) {
            throw new IllegalStateException("PAID order without pg payment id: " + orderId);
        }
        return new RefundBegin(token, payment.pgPaymentId());
    }

    /** TX-C for refund: PAID -> REFUNDED with stock/coupon give-back. */
    @Transactional
    public OrderResponse completeRefund(long orderId, UUID token) {
        Instant now = Times.now(clock);
        if (orders.completeRefund(orderId, token, now) != 1) {
            log.error("Refund completion lost the lease for order {}; manual reconciliation needed", orderId);
            throw Problems.operationInProgress("REFUND");
        }
        releaser.release(orderId, now);
        payments.markRefunded(orderId, now);
        return assembler.load(orderId);
    }

    private OrderRow loadOrder(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> Problems.orderNotFound(orderId));
    }
}
