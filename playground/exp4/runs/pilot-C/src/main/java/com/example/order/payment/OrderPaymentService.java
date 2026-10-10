package com.example.order.payment;

import com.example.order.common.ApiException;
import com.example.order.common.ApiResult;
import com.example.order.common.ErrorCode;
import com.example.order.order.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 결제·취소 오케스트레이션. 트랜잭션을 갖지 않는다 — PG 호출은 트랜잭션·커넥션 밖.
 * Tx1(표식 선점) → PG → Tx2(결과 반영).
 */
@Service
public class OrderPaymentService {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentService.class);

    private final OrderPaymentTx tx;
    private final PaymentGatewayClient gateway;

    public OrderPaymentService(OrderPaymentTx tx, PaymentGatewayClient gateway) {
        this.tx = tx;
        this.gateway = gateway;
    }

    public ApiResult pay(long orderId, String cardToken, String idempotencyKey, long idempotencyRecordId) {
        OrderPaymentTx.PayBegin begin = tx.beginPay(orderId, idempotencyRecordId);
        if (begin instanceof OrderPaymentTx.PayBegin.Done done) {
            return done.result();
        }
        if (begin instanceof OrderPaymentTx.PayBegin.Expired) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Order payment window has expired.");
        }
        long amount = ((OrderPaymentTx.PayBegin.Proceed) begin).amount();

        PgResult pg;
        try {
            pg = gateway.pay(orderId, amount, cardToken, idempotencyKey);
        } catch (RuntimeException e) {
            pg = PgResult.unavailable();
        }
        OrderPaymentTx.PayFinish finish;
        try {
            finish = tx.finishPay(orderId, pg, idempotencyRecordId);
        } catch (RuntimeException e) {
            clearMarkerQuietly(orderId);
            throw e;
        }
        return switch (finish.kind()) {
            case APPROVED -> finish.result();
            case DECLINED -> throw new ApiException(ErrorCode.PAYMENT_DECLINED, "The payment was declined.");
            case UNAVAILABLE -> throw unavailable();
        };
    }

    public OrderResponse cancel(long orderId) {
        OrderPaymentTx.CancelBegin begin = tx.beginCancel(orderId);
        if (begin instanceof OrderPaymentTx.CancelBegin.Done done) {
            return done.response();
        }
        if (begin instanceof OrderPaymentTx.CancelBegin.Expired) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Order payment window has expired.");
        }
        String paymentId = ((OrderPaymentTx.CancelBegin.Proceed) begin).paymentId();

        boolean refunded;
        try {
            refunded = gateway.refund(paymentId);
        } catch (RuntimeException e) {
            refunded = false;
        }
        OrderResponse response;
        try {
            response = tx.finishRefund(orderId, refunded);
        } catch (RuntimeException e) {
            clearMarkerQuietly(orderId);
            throw e;
        }
        if (response == null) {
            throw unavailable();
        }
        return response;
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "The payment gateway is unavailable.");
    }

    private void clearMarkerQuietly(long orderId) {
        try {
            tx.clearMarker(orderId);
        } catch (RuntimeException e) {
            log.error("Failed to clear gateway marker for order {}", orderId, e);
        }
    }
}
