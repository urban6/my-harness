package com.example.order.orders;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import com.example.order.idempotency.IdempotencyScope;
import com.example.order.idempotency.IdempotencyStore;
import com.example.order.idempotency.RequestHasher;
import com.example.order.idempotency.StoredResponse;
import com.example.order.orders.dto.CreateOrderRequest;
import com.example.order.orders.dto.PayRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 멱등 오케스트레이션. 트랜잭션이 없는 빈이다 (begin/release는 각각 별도 단기 트랜잭션). */
@Component
public class OrderFacade {

    private static final Logger log = LoggerFactory.getLogger(OrderFacade.class);

    private final IdempotencyStore idempotencyStore;
    private final RequestHasher hasher;
    private final OrderService orderService;
    private final PaymentService paymentService;

    public OrderFacade(IdempotencyStore idempotencyStore, RequestHasher hasher, OrderService orderService,
                       PaymentService paymentService) {
        this.idempotencyStore = idempotencyStore;
        this.hasher = hasher;
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    public StoredResponse create(String userId, String idempotencyKey, CreateOrderRequest request) {
        String hash = hasher.hash(IdempotencyScope.ORDER_CREATE, userId, "/api/orders", request);
        IdempotencyStore.BeginResult begin = idempotencyStore.begin(IdempotencyScope.ORDER_CREATE, idempotencyKey, hash);
        if (begin instanceof IdempotencyStore.Replay replay) {
            return replay.response();
        }
        long recordId = ((IdempotencyStore.Acquired) begin).recordId();
        try {
            return orderService.create(userId, request, recordId);
        } catch (RuntimeException e) {
            releaseQuietly(recordId);
            throw e;
        }
    }

    public StoredResponse pay(long orderId, String userId, String idempotencyKey, PayRequest request) {
        String path = "/api/orders/" + orderId + "/pay";
        String hash = hasher.hash(IdempotencyScope.ORDER_PAY, userId, path, request);
        IdempotencyStore.BeginResult begin = idempotencyStore.begin(IdempotencyScope.ORDER_PAY, idempotencyKey, hash);
        if (begin instanceof IdempotencyStore.Replay replay) {
            return replay.response();
        }
        long recordId = ((IdempotencyStore.Acquired) begin).recordId();
        PaymentService.PayOutcome outcome;
        try {
            outcome = paymentService.pay(orderId, request.cardToken(), idempotencyKey, recordId);
        } catch (RuntimeException e) {
            releaseQuietly(recordId);
            throw e;
        }
        if (outcome instanceof PaymentService.Approved approved) {
            return approved.response();
        }
        // Declined: 서비스 트랜잭션(PAYMENT_FAILED)은 이미 커밋됨. 오류이므로 키를 해제하고 402.
        releaseQuietly(recordId);
        throw new BusinessException(ErrorCode.PAYMENT_DECLINED, "결제가 거절되었습니다.");
    }

    private void releaseQuietly(long recordId) {
        try {
            idempotencyStore.release(recordId);
        } catch (RuntimeException ex) {
            log.error("멱등 레코드 해제 실패: id={}", recordId, ex);
        }
    }
}
