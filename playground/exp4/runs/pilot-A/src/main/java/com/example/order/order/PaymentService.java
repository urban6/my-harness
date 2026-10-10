package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Ids;
import com.example.order.gateway.PaymentGateway;
import com.example.order.gateway.PaymentGateway.ChargeResult;
import com.example.order.gateway.PaymentGateway.GatewayUnavailableException;
import com.example.order.order.OrderDtos.OrderResponse;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제·환불. 외부 PG 호출 동안 DB 락·커넥션을 쥐지 않도록 3단계로 나눈다.
 * <ol>
 *   <li>주문을 잠그고 상태를 검사한 뒤 "PG 호출 중" 표시를 남기고 커밋한다 (동시 요청은 여기서 걸러진다).</li>
 *   <li>트랜잭션 밖에서 PG를 호출한다.</li>
 *   <li>주문을 다시 잠그고 결과를 반영한다.</li>
 * </ol>
 */
@Service
public class PaymentService {

    private final PurchaseOrderRepository orders;
    private final Reservations reservations;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(PurchaseOrderRepository orders, Reservations reservations, PaymentGateway gateway,
                          TransactionTemplate tx, Clock clock) {
        this.orders = orders;
        this.reservations = reservations;
        this.gateway = gateway;
        this.tx = tx;
        this.clock = clock;
    }

    private record Started(OrderResponse done, long totalPrice) {
    }

    private record Settled(OrderResponse response, boolean declined) {
    }

    public OrderResponse pay(String rawId, String cardToken, String idempotencyKey) {
        long id = Ids.parse(rawId, ErrorCode.ORDER_NOT_FOUND);

        Started started = tx.execute(status -> {
            PurchaseOrder order = lock(id, rawId);
            Instant now = clock.instant();
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.isExpiredAt(now)
                    || order.isGatewayCallInFlight(now)) {
                throw Reservations.invalidState(order, "pay");
            }
            if (order.getTotalPrice() == 0) { // R5.7: PG 호출 없이 승인 처리
                approve(order, now, null);
                return new Started(OrderResponse.from(order), 0);
            }
            order.markGatewayCallStarted(now);
            return new Started(null, order.getTotalPrice());
        });
        if (started.done() != null) {
            return started.done();
        }

        ChargeResult result;
        try {
            result = gateway.charge(idempotencyKey, id, started.totalPrice(), cardToken);
        } catch (RuntimeException e) {
            clearGatewayCall(id);
            throw unavailable(e);
        }

        Settled settled = tx.execute(status -> {
            PurchaseOrder order = lock(id, rawId);
            order.clearGatewayCall();
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                throw Reservations.invalidState(order, "pay");
            }
            if (result.approved()) {
                approve(order, clock.instant(), result.paymentId());
                return new Settled(OrderResponse.from(order), false);
            }
            order.changeStatus(OrderStatus.PAYMENT_FAILED);
            reservations.release(order);
            return new Settled(OrderResponse.from(order), true);
        });
        if (settled.declined()) {
            throw new ApiException(ErrorCode.PAYMENT_DECLINED, "Payment was declined");
        }
        return settled.response();
    }

    public OrderResponse cancel(String rawId) {
        long id = Ids.parse(rawId, ErrorCode.ORDER_NOT_FOUND);

        record Phase1(OrderResponse done, String paymentId) {
        }
        Phase1 phase1 = tx.execute(status -> {
            PurchaseOrder order = lock(id, rawId);
            Instant now = clock.instant();
            if (order.isGatewayCallInFlight(now)) {
                throw Reservations.invalidState(order, "cancel");
            }
            switch (order.getStatus()) {
                case PENDING_PAYMENT -> {
                    // 기한이 지난 주문은 곧 EXPIRED가 되므로 취소할 수 없다.
                    if (order.isExpiredAt(now)) {
                        throw Reservations.invalidState(order, "cancel");
                    }
                    order.changeStatus(OrderStatus.CANCELLED);
                    reservations.release(order);
                    return new Phase1(OrderResponse.from(order), null);
                }
                case PAID -> {
                    if (order.getPaymentId() == null) { // 0원 주문: 환불할 결제가 없다.
                        order.changeStatus(OrderStatus.REFUNDED);
                        reservations.refund(order);
                        return new Phase1(OrderResponse.from(order), null);
                    }
                    order.markGatewayCallStarted(now);
                    return new Phase1(null, order.getPaymentId());
                }
                default -> throw Reservations.invalidState(order, "cancel");
            }
        });
        if (phase1.done() != null) {
            return phase1.done();
        }

        try {
            gateway.refund(phase1.paymentId());
        } catch (RuntimeException e) {
            clearGatewayCall(id);
            throw unavailable(e);
        }

        return tx.execute(status -> {
            PurchaseOrder order = lock(id, rawId);
            order.clearGatewayCall();
            if (order.getStatus() != OrderStatus.PAID) {
                throw Reservations.invalidState(order, "cancel");
            }
            order.changeStatus(OrderStatus.REFUNDED);
            reservations.refund(order);
            return OrderResponse.from(order);
        });
    }

    private PurchaseOrder lock(long id, String rawId) {
        return orders.findByIdForUpdate(id).orElseThrow(() -> OrderService.notFound(rawId));
    }

    private void approve(PurchaseOrder order, Instant now, String paymentId) {
        order.markPaid(now, paymentId);
        reservations.sell(order);
    }

    private void clearGatewayCall(long id) {
        try {
            tx.executeWithoutResult(status -> orders.findByIdForUpdate(id).ifPresent(PurchaseOrder::clearGatewayCall));
        } catch (RuntimeException ignored) {
            // 표시는 일정 시간 뒤 스스로 무효가 된다.
        }
    }

    private static ApiException unavailable(RuntimeException e) {
        String reason = e instanceof GatewayUnavailableException ? e.getMessage() : "unexpected gateway failure";
        return new ApiException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "Payment gateway unavailable: " + reason);
    }
}
