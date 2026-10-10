package com.example.order.order;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import com.example.order.order.dto.OrderResponse;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PaymentResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제·만료·취소·배송 상태 전이.
 *
 * <p>PG를 호출하는 동안 주문 행 락을 유지한다. 같은 주문에 대한 동시 결제·취소는 직렬화되어
 * PG 결제 요청이 최대 한 번만 나가고, PG 장애(예외)면 트랜잭션이 롤백되어 아무것도 바뀌지 않는다.
 */
@Service
@Transactional
public class OrderLifecycleService {

    private final OrderRepository orderRepository;
    private final ReservationService reservationService;
    private final PaymentGatewayClient paymentGateway;
    private final Clock clock;

    public OrderLifecycleService(OrderRepository orderRepository, ReservationService reservationService,
                                 PaymentGatewayClient paymentGateway, Clock clock) {
        this.orderRepository = orderRepository;
        this.reservationService = reservationService;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
    }

    /** 결제 거절은 주문을 PAYMENT_FAILED로 바꾼 뒤 402로 알리므로, 그 변경은 커밋한다. */
    @Transactional(noRollbackFor = PaymentDeclinedException.class)
    public OrderResponse pay(Long orderId, String cardToken, String idempotencyKey) {
        Order order = lock(orderId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.isPaymentExpiredAt(now)) {
            throw Order.invalidState(order);
        }

        String paymentId = null;
        if (order.getTotalPrice() > 0) {
            PaymentResult result = paymentGateway.pay(idempotencyKey, order.getId(), order.getTotalPrice(), cardToken);
            if (!result.approved()) {
                order.markPaymentFailed();
                reservationService.releaseReservation(order);
                throw new PaymentDeclinedException(order.getId());
            }
            paymentId = result.paymentId();
        }
        order.markPaid(now, paymentId);
        reservationService.confirmSale(order);
        return OrderResponse.from(order);
    }

    public OrderResponse cancel(Long orderId) {
        Order order = lock(orderId);
        switch (order.getStatus()) {
            case PENDING_PAYMENT -> {
                if (order.isPaymentExpiredAt(clock.instant())) {
                    throw Order.invalidState(order);
                }
                order.cancel();
                reservationService.releaseReservation(order);
            }
            case PAID -> {
                if (order.getPaymentId() != null) {
                    paymentGateway.refund(order.getPaymentId());
                }
                order.refund();
                reservationService.restock(order);
            }
            default -> throw Order.invalidState(order);
        }
        return OrderResponse.from(order);
    }

    public OrderResponse ship(Long orderId) {
        Order order = lock(orderId);
        order.ship();
        return OrderResponse.from(order);
    }

    public OrderResponse deliver(Long orderId) {
        Order order = lock(orderId);
        order.deliver();
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public List<Long> findExpiredPendingOrderIds(int limit) {
        return orderRepository.findIdsByStatusAndExpiresAtBefore(
                OrderStatus.PENDING_PAYMENT, clock.instant(), Limit.of(limit));
    }

    /** 결제 기한이 지난 결제 대기 주문을 만료시키고 예약·쿠폰 사용을 복원한다. 이미 처리됐으면 무시한다. */
    public void expire(Long orderId) {
        Order order = lock(orderId);
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT && order.isPaymentExpiredAt(clock.instant())) {
            order.expire();
            reservationService.releaseReservation(order);
        }
    }

    private Order lock(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> OrderService.notFound(orderId));
    }

    public static class PaymentDeclinedException extends BusinessException {
        public PaymentDeclinedException(Long orderId) {
            super(ErrorCode.PAYMENT_DECLINED, "결제가 거절되었습니다: orderId=" + orderId);
        }
    }
}
