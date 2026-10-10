package com.example.order.orders;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import com.example.order.common.time.Times;
import com.example.order.idempotency.IdempotencyStore;
import com.example.order.idempotency.StoredResponse;
import com.example.order.orders.dto.OrderResponse;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PgPaymentResult;
import com.example.order.payment.PgPaymentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    /** 결제 결과. 거절은 예외가 아니라 값으로 반환해야 PAYMENT_FAILED 전이가 커밋된다. */
    public sealed interface PayOutcome permits Approved, Declined {
    }

    public record Approved(StoredResponse response) implements PayOutcome {
    }

    public record Declined() implements PayOutcome {
    }

    private final OrderRepository orderRepository;
    private final OrderInventory inventory;
    private final IdempotencyStore idempotencyStore;
    private final PaymentGatewayClient pgClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PaymentService(OrderRepository orderRepository, OrderInventory inventory,
                          IdempotencyStore idempotencyStore, PaymentGatewayClient pgClient,
                          ObjectMapper objectMapper, Clock clock) {
        this.orderRepository = orderRepository;
        this.inventory = inventory;
        this.idempotencyStore = idempotencyStore;
        this.pgClient = pgClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 주문 행 잠금(FOR UPDATE)을 PG 호출 동안 유지한다 (§7). */
    @Transactional
    public PayOutcome pay(long orderId, String cardToken, String idempotencyKey, long idemRecordId) {
        // ① 잠금 + 404
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: id=" + orderId));

        // ② 상태·만료 검사 -> 409
        Instant now = Times.now(clock);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !now.isBefore(order.getExpiresAt())) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "결제할 수 없는 주문 상태입니다: id=" + orderId + ", status=" + order.getStatus());
        }

        // ③ PG 호출 (total=0이면 생략)
        String paymentId = null;
        if (order.getTotalPrice() > 0) {
            PgPaymentResult result = pgClient.pay(orderId, order.getTotalPrice(), cardToken, idempotencyKey);
            paymentId = result.paymentId();
            if (result.status() == PgPaymentStatus.DECLINED) {
                // ④ 거절: PAYMENT_FAILED + 예약·쿠폰 복원, 커밋
                order.markPaymentFailed(paymentId);
                inventory.releaseReservation(order);
                inventory.releaseCoupon(order);
                orderRepository.flush();
                return new Declined();
            }
        }

        // ⑤ 승인 (또는 total=0)
        order.markPaid(paymentId, Times.now(clock));
        inventory.commitReservation(order);
        orderRepository.flush();
        String json = toJson(OrderResponse.from(order));
        idempotencyStore.complete(idemRecordId, 200, json, null);
        return new Approved(new StoredResponse(200, json, null));
    }

    private String toJson(OrderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 직렬화 실패", e);
        }
    }
}
