package com.example.order.order;

import java.time.Instant;

import com.example.order.common.Times;
import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import com.example.order.order.dto.OrderResponse;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PaymentResult;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제·취소(환불). 주문 행을 잠근 채 PG를 호출해 같은 주문의 동시 결제가 PG를 한 번만 부르게 한다(R10.5).
 * PG 호출은 타임아웃(2초)으로 묶여 있어 잠금 보유 시간도 그만큼으로 제한된다.
 */
@Service
public class OrderPaymentService {

    private final OrderRepository orderRepository;
    private final OrderInventory inventory;
    private final PaymentGatewayClient paymentGateway;

    public OrderPaymentService(OrderRepository orderRepository, OrderInventory inventory,
                               PaymentGatewayClient paymentGateway) {
        this.orderRepository = orderRepository;
        this.inventory = inventory;
        this.paymentGateway = paymentGateway;
    }

    /** 거절(402)이어도 PAYMENT_FAILED 전이와 예약·쿠폰 복원은 커밋한다. PG 장애(503)는 전부 롤백한다. */
    @Transactional(noRollbackFor = PaymentDeclinedException.class)
    public OrderResponse pay(long orderId, String cardToken, String idempotencyKey) {
        Order order = orderRepository.findWithLockById(orderId).orElseThrow(() -> OrderService.notFound(orderId));
        Instant now = Times.now();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.isPaymentExpiredAt(now)) {
            throw invalidState(order, "결제할 수 없는 주문입니다");
        }

        if (order.getTotalPrice() == 0) {
            approve(order, null, now);
            return OrderResponse.from(order);
        }

        PaymentResult result = paymentGateway.pay(order.getId(), order.getTotalPrice(), cardToken, idempotencyKey);
        if (!result.approved()) {
            order.markPaymentFailed();
            inventory.releaseReservations(order);
            inventory.restoreCoupon(order);
            throw new PaymentDeclinedException(orderId);
        }
        approve(order, result.paymentId(), Times.now());
        return OrderResponse.from(order);
    }

    private void approve(Order order, String paymentId, Instant paidAt) {
        order.markPaid(paymentId, paidAt);
        inventory.confirmSale(order);
    }

    @Transactional
    public OrderResponse cancel(long orderId) {
        Order order = orderRepository.findWithLockById(orderId).orElseThrow(() -> OrderService.notFound(orderId));
        switch (order.getStatus()) {
            case PENDING_PAYMENT -> {
                if (order.isPaymentExpiredAt(Times.now())) {
                    throw invalidState(order, "결제 기한이 지난 주문입니다");
                }
                order.cancel();
                inventory.releaseReservations(order);
                inventory.restoreCoupon(order);
            }
            case PAID -> {
                if (order.getPaymentId() != null) { // 0원 결제는 PG를 거치지 않았다
                    paymentGateway.refund(order.getPaymentId());
                }
                order.refund();
                inventory.restock(order);
                inventory.restoreCoupon(order);
            }
            default -> throw invalidState(order, "취소할 수 없는 주문입니다");
        }
        return OrderResponse.from(order);
    }

    private static BusinessException invalidState(Order order, String reason) {
        return new BusinessException(ErrorCode.INVALID_STATE,
                "%s: id=%d, status=%s".formatted(reason, order.getId(), order.getStatus()));
    }
}
