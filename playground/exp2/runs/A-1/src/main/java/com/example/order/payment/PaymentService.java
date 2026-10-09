package com.example.order.payment;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import com.example.order.order.Order;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.order.OrderInventory;
import com.example.order.order.OrderRepository;
import com.example.order.order.OrderStatus;
import com.example.order.payment.PaymentGatewayClient.PaymentResult;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private final OrderRepository orderRepository;
    private final OrderInventory inventory;
    private final PaymentGatewayClient paymentGateway;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(OrderRepository orderRepository, OrderInventory inventory,
                          PaymentGatewayClient paymentGateway, TransactionTemplate tx, Clock clock) {
        this.orderRepository = orderRepository;
        this.inventory = inventory;
        this.paymentGateway = paymentGateway;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * 주문 행을 잠근 채로 PG를 호출한다. 같은 주문에 대한 다른 결제 요청은 잠금을 기다린 뒤
     * 바뀐 상태를 보고 409가 되므로 PG 결제 요청은 최대 한 번이다 (R10.5).
     * PG 장애면 예외로 롤백되어 주문·재고·쿠폰은 그대로다. 거절은 PAYMENT_FAILED를 커밋한 뒤 402로 응답한다.
     */
    public OrderResponse pay(long orderId, String cardToken, String idempotencyKey) {
        Outcome outcome = tx.execute(status -> {
            Order order = orderRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order " + orderId + " not found"));
            if (!order.isPayableAt(Times.now(clock))) {
                String reason = order.getStatus() == OrderStatus.PENDING_PAYMENT ? "payment window expired"
                        : "status is " + order.getStatus();
                throw new ApiException(ErrorCode.INVALID_STATE, "Order " + orderId + " cannot be paid: " + reason);
            }

            if (order.getTotalPrice() == 0) {
                approve(order, null);
                return new Outcome(OrderResponse.from(order), true);
            }

            PaymentResult result = paymentGateway.pay(idempotencyKey, orderId, order.getTotalPrice(), cardToken);
            if (result.approved()) {
                approve(order, result.paymentId());
            } else {
                order.markPaymentFailed();
                inventory.releaseReservation(order);
            }
            return new Outcome(OrderResponse.from(order), result.approved());
        });

        if (!outcome.approved()) {
            throw new ApiException(ErrorCode.PAYMENT_DECLINED, "Payment for order " + orderId + " was declined");
        }
        return outcome.response();
    }

    private void approve(Order order, String paymentId) {
        order.markPaid(paymentId, Times.now(clock));
        inventory.sellReserved(order);
    }

    private record Outcome(OrderResponse response, boolean approved) {
    }
}
