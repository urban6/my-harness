package com.example.order.order;

import com.example.order.common.error.ApiException;
import com.example.order.common.error.ErrorCode;
import com.example.order.common.error.InvalidStateException;
import com.example.order.common.error.PaymentDeclinedException;
import com.example.order.common.time.TimeProvider;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.idempotency.StoredResponse;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PgPayResult;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제(R5)·취소/환불(R7)·배송(R8). 한 트랜잭션에서 주문은 1건만 락한다(주문 → 상품 asc → 쿠폰).
 */
@Service
public class OrderLifecycleService {

    private final OrderRepository orderRepository;
    private final OrderResourceSupport resources;
    private final PaymentGatewayClient paymentGateway;
    private final IdempotencyService idempotency;
    private final TimeProvider time;

    public OrderLifecycleService(OrderRepository orderRepository, OrderResourceSupport resources,
                                 PaymentGatewayClient paymentGateway, IdempotencyService idempotency,
                                 TimeProvider time) {
        this.orderRepository = orderRepository;
        this.resources = resources;
        this.paymentGateway = paymentGateway;
        this.idempotency = idempotency;
        this.time = time;
    }

    /** 주문 행 락을 쥔 채 PG를 호출한다(R10.5). */
    @Transactional(noRollbackFor = {InvalidStateException.class, PaymentDeclinedException.class})
    public StoredResponse pay(long orderId, String cardToken, String idempotencyKey, long idempotencyRecordId) {
        Order order = lockOrder(orderId);
        Instant now = time.now();
        if (order.isExpiredAt(now)) {
            resources.releaseReservation(order);
            order.expire();
            throw new InvalidStateException("결제 가능 시간이 지나 주문이 만료되었습니다: id=" + orderId);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new InvalidStateException("결제할 수 없는 주문 상태입니다: " + order.getStatus());
        }

        String pgPaymentId = null;
        if (order.getTotalPrice() > 0) {
            PgPayResult result = paymentGateway.pay(idempotencyKey, orderId, order.getTotalPrice(), cardToken);
            if (!result.approved()) {
                resources.releaseReservation(order);
                order.markPaymentFailed(result.paymentId());
                throw new PaymentDeclinedException("결제가 거절되었습니다: orderId=" + orderId);
            }
            pgPaymentId = result.paymentId();
        }

        resources.commitSale(order);
        order.markPaid(time.now(), pgPaymentId);
        return idempotency.complete(idempotencyRecordId, 200, OrderResponse.from(order), null);
    }

    @Transactional(noRollbackFor = InvalidStateException.class)
    public OrderResponse cancel(long orderId) {
        Order order = lockOrder(orderId);
        Instant now = time.now();
        if (order.isExpiredAt(now)) {
            resources.releaseReservation(order);
            order.expire();
            throw new InvalidStateException("결제 가능 시간이 지나 주문이 만료되었습니다: id=" + orderId);
        }
        switch (order.getStatus()) {
            case PENDING_PAYMENT -> {
                resources.releaseReservation(order);
                order.cancel();
            }
            case PAID -> {
                if (order.getPgPaymentId() != null) {
                    paymentGateway.refund(order.getPgPaymentId());
                }
                resources.restock(order);
                order.refund();
            }
            default -> throw new InvalidStateException("취소할 수 없는 주문 상태입니다: " + order.getStatus());
        }
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse ship(long orderId) {
        Order order = lockOrder(orderId);
        order.ship();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse deliver(long orderId) {
        Order order = lockOrder(orderId);
        order.deliver();
        return OrderResponse.from(order);
    }

    private Order lockOrder(long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: id=" + orderId));
    }
}
