package com.example.order.payment;

import com.example.order.common.ApiException;
import com.example.order.common.ApiResult;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import com.example.order.idempotency.IdempotencyStore;
import com.example.order.order.OrderEntity;
import com.example.order.order.OrderOperations;
import com.example.order.order.OrderRepository;
import com.example.order.order.OrderResponse;
import com.example.order.order.OrderStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제·환불의 짧은 트랜잭션들 (PG 호출은 이 빈 밖, 트랜잭션 밖에서 일어난다).
 * 모든 메서드는 주문 행 FOR UPDATE 로 시작한다 (락 순서: 주문 → 상품 → 쿠폰).
 */
@Service
public class OrderPaymentTx {

    public sealed interface PayBegin permits PayBegin.Done, PayBegin.Expired, PayBegin.Proceed {
        record Done(ApiResult result) implements PayBegin { }
        record Expired() implements PayBegin { }
        record Proceed(long amount) implements PayBegin { }
    }

    public sealed interface CancelBegin permits CancelBegin.Done, CancelBegin.Expired, CancelBegin.Proceed {
        record Done(OrderResponse response) implements CancelBegin { }
        record Expired() implements CancelBegin { }
        record Proceed(String paymentId) implements CancelBegin { }
    }

    /** 결제 결과 반영 결과. 거절은 커밋이 필요하므로 예외가 아닌 값으로 돌려준다. */
    public record PayFinish(PgResult.Kind kind, ApiResult result) { }

    private final OrderRepository orderRepository;
    private final OrderOperations operations;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrderPaymentTx(OrderRepository orderRepository, OrderOperations operations,
                          IdempotencyStore idempotencyStore, ObjectMapper objectMapper, Clock clock) {
        this.orderRepository = orderRepository;
        this.operations = operations;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---- 결제 ----

    /** Tx1: 선점 표식 기록(또는 0원 즉시 결제 / 지연 만료). */
    @Transactional
    public PayBegin beginPay(long orderId, long idempotencyRecordId) {
        OrderEntity order = lock(orderId);
        Instant now = Times.now(clock);
        boolean inflight = order.hasActiveGatewayCall(now);
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT && order.isExpiredAt(now) && !inflight) {
            operations.expire(order, now);
            return new PayBegin.Expired();
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || inflight) {
            throw new ApiException(ErrorCode.INVALID_STATE, inflight
                    ? "A payment for this order is already in progress."
                    : "Order is " + order.getStatus() + "; expected PENDING_PAYMENT.");
        }
        if (order.getTotalPrice() == 0) {
            operations.commitSale(order);
            order.markPaid(null, now);
            return new PayBegin.Done(completePaid(order, idempotencyRecordId));
        }
        order.beginGatewayCall(now);
        return new PayBegin.Proceed(order.getTotalPrice());
    }

    /** Tx2: 표식 해제 후 PG 결과 반영. */
    @Transactional
    public PayFinish finishPay(long orderId, PgResult pg, long idempotencyRecordId) {
        OrderEntity order = lock(orderId);
        Instant now = Times.now(clock);
        order.clearGatewayCall();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Order " + orderId + " left PENDING_PAYMENT during PG call: "
                    + order.getStatus());
        }
        switch (pg.kind()) {
            case APPROVED -> {
                operations.commitSale(order);
                order.markPaid(pg.paymentId(), now);
                return new PayFinish(PgResult.Kind.APPROVED, completePaid(order, idempotencyRecordId));
            }
            case DECLINED -> {
                operations.failPayment(order, now);
                return new PayFinish(PgResult.Kind.DECLINED, null);
            }
            default -> {
                return new PayFinish(PgResult.Kind.UNAVAILABLE, null);
            }
        }
    }

    // ---- 취소·환불 ----

    @Transactional
    public CancelBegin beginCancel(long orderId) {
        OrderEntity order = lock(orderId);
        Instant now = Times.now(clock);
        boolean inflight = order.hasActiveGatewayCall(now);
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT && order.isExpiredAt(now) && !inflight) {
            operations.expire(order, now);
            return new CancelBegin.Expired();
        }
        if (inflight) {
            throw new ApiException(ErrorCode.INVALID_STATE, "A payment operation for this order is in progress.");
        }
        switch (order.getStatus()) {
            case PENDING_PAYMENT -> {
                operations.cancelPending(order, now);
                return new CancelBegin.Done(OrderResponse.from(order));
            }
            case PAID -> {
                if (order.getPaymentId() == null) { // 0원 주문: PG 기록 없음
                    operations.refund(order, now);
                    return new CancelBegin.Done(OrderResponse.from(order));
                }
                order.beginGatewayCall(now);
                return new CancelBegin.Proceed(order.getPaymentId());
            }
            default -> throw new ApiException(ErrorCode.INVALID_STATE,
                    "Order is " + order.getStatus() + " and cannot be cancelled.");
        }
    }

    /** @return 환불 성공 시 주문 응답, 실패(장애) 시 null — 이때 상태는 불변. */
    @Transactional
    public OrderResponse finishRefund(long orderId, boolean refunded) {
        OrderEntity order = lock(orderId);
        Instant now = Times.now(clock);
        order.clearGatewayCall();
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("Order " + orderId + " left PAID during PG refund: " + order.getStatus());
        }
        if (!refunded) {
            return null;
        }
        operations.refund(order, now);
        return OrderResponse.from(order);
    }

    /** PG 호출 후 반영 트랜잭션이 실패했을 때 표식만 해제하는 보정. */
    @Transactional
    public void clearMarker(long orderId) {
        orderRepository.lockById(orderId).ifPresent(OrderEntity::clearGatewayCall);
    }

    private OrderEntity lock(long orderId) {
        return orderRepository.lockById(orderId).orElseThrow(() ->
                new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order " + orderId + " not found."));
    }

    private ApiResult completePaid(OrderEntity order, long idempotencyRecordId) {
        String body;
        try {
            body = objectMapper.writeValueAsString(OrderResponse.from(order));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        idempotencyStore.complete(idempotencyRecordId, 200, body, null);
        return new ApiResult(200, body, null);
    }
}
