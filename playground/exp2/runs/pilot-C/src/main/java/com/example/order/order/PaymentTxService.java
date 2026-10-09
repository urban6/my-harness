package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PgPaymentResult;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 결제의 업무 트랜잭션. 주문 행 락을 쥔 채 PG 를 호출한다 (동시 결제 직렬화, R10.5). */
@Service
public class PaymentTxService {

    public record PayOutcome(boolean declined, OrderResponse order) {
    }

    private final OrderRepository orderRepository;
    private final OrderInventory inventory;
    private final PaymentGatewayClient pgClient;
    private final Clock clock;

    public PaymentTxService(OrderRepository orderRepository, OrderInventory inventory, PaymentGatewayClient pgClient,
                            Clock clock) {
        this.orderRepository = orderRepository;
        this.inventory = inventory;
        this.pgClient = pgClient;
        this.clock = clock;
    }

    @Transactional
    public PayOutcome payInTx(long id, String idempotencyKey, String cardToken) {
        OrderEntity order = orderRepository.lockById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "order " + id + " not found"));
        OffsetDateTime now = Times.now(clock);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !now.isBefore(order.getExpiresAt())) {
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "order " + id + " cannot be paid (status " + order.getStatus() + ")");
        }

        boolean approved = true;
        String paymentId = null;
        if (order.getTotalPrice() > 0) {
            PgPaymentResult result = pgClient.pay(idempotencyKey, id, order.getTotalPrice(), cardToken);
            approved = result.approved();
            paymentId = result.paymentId();
        }

        if (approved) {
            inventory.sell(order);
            order.markPaid(paymentId, Times.now(clock));
        } else {
            inventory.releaseReservation(order);
            inventory.restoreCoupon(order);
            order.markPaymentFailed(paymentId);
        }
        orderRepository.flush();
        return new PayOutcome(!approved, OrderResponse.from(order));
    }
}
