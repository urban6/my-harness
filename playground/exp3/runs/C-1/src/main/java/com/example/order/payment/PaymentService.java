package com.example.order.payment;

import com.example.order.common.Hashing;
import com.example.order.order.OrderResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates TX-A -> PG call -> TX-C. NOT transactional: the PG call must never run inside a DB
 * transaction (R11). All transactional work lives in PaymentTxService.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public record PayOutcome(OrderResponse order, boolean replayed) {
    }

    private final PaymentTxService tx;
    private final PaymentGatewayClient gateway;

    public PaymentService(PaymentTxService tx, PaymentGatewayClient gateway) {
        this.tx = tx;
        this.gateway = gateway;
    }

    public PayOutcome pay(long orderId, String idempotencyKey, String cardToken) {
        String cardHash = Hashing.sha256Hex(cardToken); // the raw token is never stored or logged
        PaymentTxService.PayBegin begin = tx.beginPayment(orderId, idempotencyKey, cardHash);
        if (begin.replay()) {
            return new PayOutcome(begin.replayedOrder(), true);
        }
        GatewayResult result;
        try {
            result = gateway.charge(orderId, begin.amount(), cardToken, idempotencyKey);
        } catch (GatewayException e) {
            abort(orderId, begin.token(), e);
            throw e.toApiException(orderId);
        } catch (RuntimeException e) {
            abort(orderId, begin.token(), e);
            throw e;
        }
        return new PayOutcome(tx.completePayment(orderId, begin.token(), result), false);
    }

    public OrderResponse refund(long orderId) {
        PaymentTxService.RefundBegin begin = tx.beginRefund(orderId);
        try {
            gateway.refund(begin.pgPaymentId(), "refund-" + orderId);
        } catch (GatewayException e) {
            abort(orderId, begin.token(), e);
            throw e.toApiException(orderId);
        } catch (RuntimeException e) {
            abort(orderId, begin.token(), e);
            throw e;
        }
        return tx.completeRefund(orderId, begin.token());
    }

    private void abort(long orderId, UUID token, RuntimeException cause) {
        log.warn("PG call failed for order {}: {}", orderId, cause.getMessage());
        tx.abortLease(orderId, token, cause.getMessage());
    }
}
