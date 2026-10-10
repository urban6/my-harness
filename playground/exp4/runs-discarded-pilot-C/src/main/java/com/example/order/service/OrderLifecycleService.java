package com.example.order.service;

import com.example.order.config.AppClock;
import com.example.order.config.OrderProperties;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.gateway.PaymentGatewayClient;
import com.example.order.repository.OrderRepository;
import com.example.order.web.dto.OrderResponse;
import com.example.order.web.error.ApiException;
import com.example.order.web.error.ErrorCode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 취소·환불 (01 문서 7.1) 과 배송 (7.2). 모든 전이는 주문 행 FOR UPDATE 아래에서 상태를 확인한다. */
@Service
public class OrderLifecycleService {

    private sealed interface CancelStage permits CDone, CRejected, CRefund {
    }

    private record CDone(OrderResponse response) implements CancelStage {
    }

    private record CRejected(ApiException error) implements CancelStage {
    }

    private record CRefund(Instant marker, String paymentId) implements CancelStage {
    }

    private sealed interface RefundStage permits RDone, RFailed {
    }

    private record RDone(OrderResponse response) implements RefundStage {
    }

    private record RFailed(ApiException error) implements RefundStage {
    }

    private final OrderRepository orders;
    private final InventoryOps inventory;
    private final ExpiryService expiry;
    private final PaymentGatewayClient gateway;
    private final AppClock clock;
    private final OrderProperties props;
    private final TransactionTemplate tx;

    public OrderLifecycleService(OrderRepository orders, InventoryOps inventory, ExpiryService expiry,
                                 PaymentGatewayClient gateway, AppClock clock, OrderProperties props,
                                 PlatformTransactionManager tm) {
        this.orders = orders;
        this.inventory = inventory;
        this.expiry = expiry;
        this.gateway = gateway;
        this.clock = clock;
        this.props = props;
        this.tx = new TransactionTemplate(tm);
    }

    public OrderResponse cancel(long orderId) {
        CancelStage stage = DbRetry.run(() -> tx.execute(s -> cancelStage(orderId)));
        if (stage instanceof CRejected r) {
            throw r.error();
        }
        if (stage instanceof CDone d) {
            return d.response();
        }
        CRefund refund = (CRefund) stage;
        boolean refunded = gateway.refund(refund.paymentId()); // 트랜잭션 밖
        RefundStage result = DbRetry.run(() -> tx.execute(s -> refundStage(orderId, refund.marker(), refunded)));
        if (result instanceof RFailed f) {
            throw f.error();
        }
        return ((RDone) result).response();
    }

    private CancelStage cancelStage(long orderId) {
        Order order = lock(orderId);
        Instant now = clock.now();
        if (expiry.expireIfDue(order, now)) {
            return new CRejected(invalidState("Order has expired.")); // EXPIRED 전이는 커밋된다
        }
        if (order.isGatewayCallInFlight(now, props.expiry().inFlightTimeout())) {
            return new CRejected(invalidState("A payment or refund for this order is in progress."));
        }
        switch (order.getStatus()) {
            case PENDING_PAYMENT -> {
                if (order.isExpiredAt(now)) { // 표지가 오래된 경우는 위 expireIfDue 가 처리했으므로 방어적
                    return new CRejected(invalidState("Order has expired."));
                }
                order.setStatus(OrderStatus.CANCELLED);
                inventory.releaseReservation(order);
                return new CDone(OrderResponse.from(order));
            }
            case PAID -> {
                if (order.getTotalPrice() == 0) {
                    // PG 에 결제가 없었으므로 환불 호출 없이 REFUNDED (ASSUMPTION 10)
                    order.setStatus(OrderStatus.REFUNDED);
                    inventory.restockAndRestoreCoupon(order);
                    return new CDone(OrderResponse.from(order));
                }
                if (order.getPaymentId() == null) {
                    return new CRejected(new ApiException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                            "The payment gateway is unavailable."));
                }
                order.setGatewayCallStartedAt(now);
                return new CRefund(now, order.getPaymentId());
            }
            default -> {
                return new CRejected(invalidState("Order cannot be cancelled in status " + order.getStatus() + "."));
            }
        }
    }

    private RefundStage refundStage(long orderId, Instant marker, boolean refunded) {
        Order order = lock(orderId);
        if (order.getStatus() != OrderStatus.PAID || !marker.equals(order.getGatewayCallStartedAt())) {
            return new RFailed(invalidState("Order state changed during refund."));
        }
        order.setGatewayCallStartedAt(null);
        if (!refunded) {
            return new RFailed(new ApiException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                    "The payment gateway is unavailable."));
        }
        order.setStatus(OrderStatus.REFUNDED);
        inventory.restockAndRestoreCoupon(order);
        return new RDone(OrderResponse.from(order));
    }

    public OrderResponse ship(long orderId) {
        return DbRetry.run(() -> tx.execute(s -> {
            Order order = lock(orderId);
            Instant now = clock.now();
            if (order.getStatus() != OrderStatus.PAID
                    || order.isGatewayCallInFlight(now, props.expiry().inFlightTimeout())) {
                throw invalidState("Order cannot be shipped in status " + order.getStatus() + ".");
            }
            order.setStatus(OrderStatus.SHIPPED);
            return OrderResponse.from(order);
        }));
    }

    public OrderResponse deliver(long orderId) {
        return DbRetry.run(() -> tx.execute(s -> {
            Order order = lock(orderId);
            if (order.getStatus() != OrderStatus.SHIPPED) {
                throw invalidState("Order cannot be delivered in status " + order.getStatus() + ".");
            }
            order.setStatus(OrderStatus.DELIVERED);
            return OrderResponse.from(order);
        }));
    }

    private Order lock(long orderId) {
        return orders.findLockedById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order " + orderId + " not found."));
    }

    private static ApiException invalidState(String detail) {
        return new ApiException(ErrorCode.INVALID_STATE, detail);
    }
}
