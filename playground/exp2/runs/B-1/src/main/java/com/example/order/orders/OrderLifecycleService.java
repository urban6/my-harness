package com.example.order.orders;

import com.example.order.common.Times;
import com.example.order.orders.dto.OrderResponse;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PaymentResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제·취소/환불·배송·만료(R5~R8). 모든 전이는 주문 행을 잠근 뒤 수행한다.
 * <p>
 * PG 호출(최대 2초) 동안 주문 행 잠금을 유지한다. 같은 주문에 대한 결제·취소·만료가 그동안 대기하거나
 * (만료는) 건너뛰므로, 한 주문에 PG 결제 요청이 두 번 나가지 않는다(R10.5). PG 장애 시 트랜잭션이 롤백되어
 * 주문·재고·쿠폰은 바뀌지 않는다(R5.6, R7.3).
 */
@Service
public class OrderLifecycleService {

    private final OrderRepository orderRepository;
    private final OrderInventory inventory;
    private final PaymentGatewayClient paymentGateway;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OrderLifecycleService(OrderRepository orderRepository, OrderInventory inventory,
                                 PaymentGatewayClient paymentGateway, PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.orderRepository = orderRepository;
        this.inventory = inventory;
        this.paymentGateway = paymentGateway;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 결제(R5). 거절은 주문을 PAYMENT_FAILED 로 커밋한 뒤 {@link PaymentDeclinedException}을 던진다.
     *
     * @param idempotencyKey 클라이언트의 Idempotency-Key. PG 요청에 그대로 전달한다(R5.3).
     */
    public OrderResponse pay(Long orderId, String cardToken, String idempotencyKey) {
        PayOutcome outcome = transactionTemplate.execute(tx -> {
            Order order = lockOrder(orderId);
            order.ensurePayable(Times.now(clock));
            if (order.getTotalPrice() == 0) {
                approve(order, null);
                return new PayOutcome(OrderResponse.from(order), true);
            }
            PaymentResult result = paymentGateway.pay(idempotencyKey, orderId, order.getTotalPrice(), cardToken);
            if (result.approved()) {
                approve(order, result.paymentId());
            } else {
                order.markPaymentFailed();
                inventory.releaseReservation(order);
            }
            return new PayOutcome(OrderResponse.from(order), result.approved());
        });
        if (!outcome.approved()) {
            throw new PaymentDeclinedException(orderId);
        }
        return outcome.response();
    }

    private void approve(Order order, String paymentId) {
        order.markPaid(paymentId, Times.now(clock));
        inventory.commitSale(order);
    }

    /** 취소(R7): 결제 대기면 예약 해제, 결제 완료면 PG 환불 후 재고 복구. */
    @Transactional
    public OrderResponse cancel(Long orderId) {
        Order order = lockOrder(orderId);
        if (order.getStatus() == OrderStatus.PAID) {
            if (order.getPaymentId() != null) {
                paymentGateway.refund(order.getPaymentId());
            }
            order.refund();
            inventory.restock(order);
        } else {
            order.cancel();
            inventory.releaseReservation(order);
        }
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse ship(Long orderId) {
        Order order = lockOrder(orderId);
        order.ship();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse deliver(Long orderId) {
        Order order = lockOrder(orderId);
        order.deliver();
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public List<Long> findExpiredOrderIds(int limit) {
        return orderRepository.findExpiredPendingIds(Times.now(clock), Limit.of(limit));
    }

    /** 결제 만료(R6). 다른 요청이 잠근 주문(결제 중 등)은 건너뛴다. */
    @Transactional
    public boolean expire(Long orderId) {
        Instant now = Times.now(clock);
        return orderRepository.findExpirableForUpdate(orderId, now)
                .map(order -> {
                    order.expire();
                    inventory.releaseReservation(order);
                    return true;
                })
                .orElse(false);
    }

    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private record PayOutcome(OrderResponse response, boolean approved) {
    }
}
