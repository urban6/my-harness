package com.example.order.order;

import com.example.order.order.OrderService.CancelStart;
import com.example.order.order.OrderService.PaymentStart;
import com.example.order.order.dto.OrderResponse;
import com.example.order.payment.PaymentGateway;
import com.example.order.payment.PaymentResult;
import org.springframework.stereotype.Service;

/**
 * 외부 PG 를 호출하는 흐름(결제·환불). DB 트랜잭션 안에서 네트워크 호출을 하지 않도록
 * "시작(짧은 tx) → PG 호출 → 완료(짧은 tx)" 로 나눈다. 이 클래스 자체는 트랜잭션이 없다.
 *
 * <p>PG 멱등 키는 주문당 하나(order-{id})로 고정한다. PG 응답을 못 받은 채 재시도해도 중복 결제되지 않는다.
 */
@Service
public class OrderPaymentService {

    private final OrderService orderService;
    private final PaymentGateway gateway;

    public OrderPaymentService(OrderService orderService, PaymentGateway gateway) {
        this.orderService = orderService;
        this.gateway = gateway;
    }

    public OrderResponse pay(long orderId, String idempotencyKey, String cardToken) {
        orderService.expireIfDue(orderId);
        PaymentStart start = orderService.beginPayment(orderId, idempotencyKey);
        if (start.replay() != null) {
            return start.replay();
        }
        PaymentResult result;
        try {
            result = gateway.charge("order-" + orderId, orderId, start.amount(), cardToken);
        } catch (RuntimeException e) {
            orderService.abortGatewayCall(orderId);
            throw e;
        }
        return orderService.completePayment(orderId, result);
    }

    public OrderResponse cancel(long orderId) {
        orderService.expireIfDue(orderId);
        CancelStart start = orderService.beginCancel(orderId);
        if (start.done() != null) {
            return start.done();
        }
        try {
            gateway.refund(start.paymentId());
        } catch (RuntimeException e) {
            orderService.abortGatewayCall(orderId);
            throw e;
        }
        return orderService.completeRefund(orderId);
    }
}
