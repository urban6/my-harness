package com.example.order.service;

import com.example.order.config.AppClock;
import com.example.order.config.OrderProperties;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.gateway.PaymentGatewayClient;
import com.example.order.repository.OrderRepository;
import com.example.order.web.dto.OrderResponse;
import com.example.order.web.error.ApiException;
import com.example.order.web.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * POST /api/orders/{id}/pay (01 문서 6절, 02 문서 8절).
 * 오케스트레이터는 @Transactional 이 아니다: begin -> TX-A -> (PG 호출: 커넥션/락 없음) -> TX-B 를 순차 실행한다.
 * 상태를 바꾸고도 오류 응답을 내야 하는 경우(만료 전이 후 409, 거절 402, PG 장애 503)는
 * 트랜잭션이 결과 객체를 반환해 커밋한 뒤 트랜잭션 밖에서 예외를 던진다.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private sealed interface StageA permits ADone, ARejected, AProceed {
    }

    private record ADone(StoredResponse response) implements StageA {
    }

    private record ARejected(ApiException error) implements StageA {
    }

    private record AProceed(Instant marker, long amount) implements StageA {
    }

    private sealed interface StageB permits BDone, BFailed {
    }

    private record BDone(StoredResponse response) implements StageB {
    }

    private record BFailed(ApiException error) implements StageB {
    }

    private final IdempotencyService idempotency;
    private final OrderRepository orders;
    private final InventoryOps inventory;
    private final ExpiryService expiry;
    private final PaymentGatewayClient gateway;
    private final AppClock clock;
    private final OrderProperties props;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public PaymentService(IdempotencyService idempotency, OrderRepository orders, InventoryOps inventory,
                          ExpiryService expiry, PaymentGatewayClient gateway, AppClock clock, OrderProperties props,
                          ObjectMapper json, PlatformTransactionManager tm) {
        this.idempotency = idempotency;
        this.orders = orders;
        this.inventory = inventory;
        this.expiry = expiry;
        this.gateway = gateway;
        this.clock = clock;
        this.props = props;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
    }

    public StoredResponse pay(long orderId, String idemKey, String userIdHeader, String cardToken) {
        // 2단계: 멱등 키 (지문에 orderId 포함)
        IdempotencyService.BeginResult begin = idempotency.begin(IdempotencyService.Endpoint.ORDER_PAY, idemKey,
                Fingerprints.pay(userIdHeader, orderId, cardToken));
        if (begin instanceof IdempotencyService.Replay replay) {
            return replay.response();
        }
        IdempotencyService.Attempt attempt = ((IdempotencyService.Proceed) begin).attempt();
        try {
            // 3~4단계: TX-A
            StageA a = DbRetry.run(() -> tx.execute(s -> stageA(orderId, attempt)));
            if (a instanceof ARejected r) {
                throw r.error();
            }
            if (a instanceof ADone d) {
                return d.response();
            }
            AProceed proceed = (AProceed) a;

            // 5단계: PG 호출 (트랜잭션 밖, 전체 2초 데드라인)
            PaymentGatewayClient.PayResult pg = gateway.charge(orderId, proceed.amount(), cardToken, idemKey);

            // 6단계: TX-B
            StageB b = DbRetry.run(() -> tx.execute(s -> stageB(orderId, attempt, proceed.marker(), pg)));
            if (b instanceof BFailed f) {
                throw f.error();
            }
            return ((BDone) b).response();
        } catch (RuntimeException | Error e) {
            idempotency.release(attempt);
            throw e;
        }
    }

    private StageA stageA(long orderId, IdempotencyService.Attempt attempt) {
        Order order = orders.findLockedById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order " + orderId + " not found."));
        Instant now = clock.now();
        boolean expired = expiry.expireIfDue(order, now); // 만료 전이는 커밋되어야 하므로 예외로 끝내지 않는다
        if (expired) {
            return new ARejected(invalidState("Order has expired."));
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return new ARejected(invalidState("Order is not awaiting payment."));
        }
        if (order.isExpiredAt(now)) {
            // 만료됐지만 다른 결제가 진행 중이라 스위퍼가 건드리지 않는 경우
            return new ARejected(invalidState("Order has expired."));
        }
        if (order.isGatewayCallInFlight(now, props.expiry().inFlightTimeout())) {
            return new ARejected(invalidState("A payment for this order is already in progress."));
        }
        if (order.getTotalPrice() == 0) {
            // R5.7: PG 호출 없이 곧바로 승인 처리
            StoredResponse response = approve(order, null, now);
            idempotency.complete(attempt, response);
            return new ADone(response);
        }
        order.setGatewayCallStartedAt(now); // 표지: 다른 pay/cancel/ship 과 만료 스위퍼를 막는다
        return new AProceed(now, order.getTotalPrice());
    }

    private StageB stageB(long orderId, IdempotencyService.Attempt attempt, Instant marker,
                          PaymentGatewayClient.PayResult pg) {
        Order order = orders.findLockedById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order " + orderId + " not found."));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !marker.equals(order.getGatewayCallStartedAt())) {
            // 표지를 다른 요청이 인수한 극히 드문 경우: 상태를 건드리지 않는다
            log.error("Payment marker for order {} was taken over; leaving order untouched", orderId);
            return new BFailed(invalidState("Order state changed during payment."));
        }
        Instant now = clock.now();
        if (pg instanceof PaymentGatewayClient.Approved approved) {
            StoredResponse response = approve(order, approved.paymentId(), now);
            idempotency.complete(attempt, response);
            return new BDone(response);
        }
        order.setGatewayCallStartedAt(null);
        if (pg instanceof PaymentGatewayClient.Declined) {
            order.setStatus(OrderStatus.PAYMENT_FAILED);
            inventory.releaseReservation(order);
            return new BFailed(new ApiException(ErrorCode.PAYMENT_DECLINED, "The payment was declined."));
        }
        return new BFailed(new ApiException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "The payment gateway is unavailable."));
    }

    /** 승인 효과: PAID, paidAt, payment_id, 표지 해제, stock/reserved 차감. 응답 JSON 문자열을 만들어 반환. */
    private StoredResponse approve(Order order, String paymentId, Instant now) {
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(now);
        order.setPaymentId(paymentId);
        order.setGatewayCallStartedAt(null);
        inventory.confirmSale(order);
        try {
            return StoredResponse.json(200, null, json.writeValueAsString(OrderResponse.from(order)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize order response", e);
        }
    }

    private static ApiException invalidState(String detail) {
        return new ApiException(ErrorCode.INVALID_STATE, detail);
    }
}
