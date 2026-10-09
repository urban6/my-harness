package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.order.OrderDtos.CreateOrderRequest;
import com.example.order.order.OrderDtos.OrderItemRequest;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PaymentGatewayClient.PaymentResult;
import com.example.order.payment.PaymentGatewayUnavailableException;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 상태 전이를 담당한다.
 *
 * <p>잠금 순서는 항상 주문 → 상품(id 오름차순) → 쿠폰이다. 주문 생성은 주문 행을 잠그지 않으므로
 * 상품 → 쿠폰 순서만 지키면 된다. 이 순서 덕분에 동시 요청 사이에 교착 상태가 생기지 않는다 (R10.4).
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final PaymentGatewayClient paymentGateway;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration paymentTtl;

    public OrderService(OrderRepository orders, ProductRepository products, CouponRepository coupons,
                        PaymentGatewayClient paymentGateway, PlatformTransactionManager transactionManager,
                        Clock clock, @Value("${order.payment.ttl}") Duration paymentTtl) {
        this.orders = orders;
        this.products = products;
        this.coupons = coupons;
        this.paymentGateway = paymentGateway;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.paymentTtl = paymentTtl;
    }

    // ---- 생성 (R3) ----

    public OrderResponse create(String userId, CreateOrderRequest request) {
        return tx.execute(status -> {
            Instant now = Times.now(clock);

            // 404 검사: 상품(잠금 순서대로) → 쿠폰
            List<OrderItemRequest> lockOrder = request.items().stream()
                    .sorted(Comparator.comparingLong(OrderItemRequest::productId))
                    .toList();
            Map<Long, Product> locked = new HashMap<>();
            for (OrderItemRequest item : lockOrder) {
                Product product = products.findByIdForUpdate(item.productId())
                        .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                                "Product " + item.productId() + " not found"));
                locked.put(product.getId(), product);
            }
            Coupon coupon = null;
            if (request.couponCode() != null) {
                coupon = coupons.findByCodeForUpdate(request.couponCode())
                        .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND,
                                "Coupon " + request.couponCode() + " not found"));
            }

            // 409 검사: 재고 → 쿠폰
            for (OrderItemRequest item : lockOrder) {
                Product product = locked.get(item.productId());
                if (product.available() < item.quantity()) {
                    throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                            "Product " + product.getId() + " has only " + product.available() + " available");
                }
            }
            List<OrderItem> items = new ArrayList<>();
            long subtotal = 0;
            for (OrderItemRequest item : request.items()) {
                OrderItem orderItem = new OrderItem(item.productId(), item.quantity(),
                        locked.get(item.productId()).getPrice());
                items.add(orderItem);
                subtotal = Math.addExact(subtotal, orderItem.lineTotal());
            }
            long discount = 0;
            if (coupon != null) {
                checkCouponApplicable(coupon, userId, subtotal, now);
                discount = coupon.discountFor(subtotal);
            }

            // 모든 검사를 통과했으므로 예약·쿠폰 사용을 반영한다 (같은 트랜잭션: 전부 또는 전무).
            for (OrderItemRequest item : lockOrder) {
                locked.get(item.productId()).reserve(item.quantity());
            }
            if (coupon != null) {
                coupon.use();
            }
            Order order = orders.saveAndFlush(new Order(userId, items, request.couponCode(), subtotal, discount,
                    now, now.plus(paymentTtl)));
            return OrderResponse.from(order);
        });
    }

    private void checkCouponApplicable(Coupon coupon, String userId, long subtotal, Instant now) {
        if (!coupon.isValidAt(now)) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "Coupon " + coupon.getCode() + " is not valid at this time");
        }
        if (subtotal < coupon.getMinOrderAmount()) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "Order amount is below the coupon minimum of " + coupon.getMinOrderAmount());
        }
        if (orders.existsByUserAndCouponInStatuses(userId, coupon.getCode(), OrderStatus.COUPON_IN_USE)) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "Coupon " + coupon.getCode() + " is already in use by this user");
        }
        if (coupon.isExhausted()) {
            throw new ApiException(ErrorCode.COUPON_EXHAUSTED, "Coupon " + coupon.getCode() + " is exhausted");
        }
    }

    // ---- 조회 (R3.5) ----

    public OrderResponse get(long orderId) {
        return tx.execute(status -> OrderResponse.from(orders.findById(orderId)
                .orElseThrow(() -> notFound(orderId))));
    }

    // ---- 결제 (R5) ----

    private sealed interface Outcome permits Done, Declined, InvalidState {
    }

    private record Done(OrderResponse order) implements Outcome {
    }

    private record Declined(OrderResponse order) implements Outcome {
    }

    private record InvalidState(String detail) implements Outcome {
    }

    /**
     * 주문 행을 잠근 채로 PG를 호출한다. 같은 주문에 대한 다른 결제·취소 요청은 잠금에서 기다렸다가
     * 바뀐 상태를 보고 409로 끝나므로 PG 결제 요청은 최대 한 번이다 (R10.5).
     * PG 장애면 트랜잭션을 롤백해 주문·재고·쿠폰을 그대로 둔다 (R5.6).
     */
    public OrderResponse pay(long orderId, String idempotencyKey, String cardToken) {
        Outcome outcome = withGateway(() -> tx.execute(status -> {
            Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> notFound(orderId));
            InvalidState invalid = expireIfDeadlinePassed(order);
            if (invalid != null) {
                return invalid;
            }
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                return invalidState(order);
            }
            if (order.getTotalPrice() == 0) {
                confirmPayment(order, null);
                return new Done(OrderResponse.from(order));
            }
            PaymentResult result = paymentGateway.pay(idempotencyKey, order.getId(), order.getTotalPrice(),
                    cardToken);
            if (result.approved()) {
                confirmPayment(order, result.paymentId());
                return new Done(OrderResponse.from(order));
            }
            closeUnpaid(order, OrderStatus.PAYMENT_FAILED);
            return new Declined(OrderResponse.from(order));
        }));
        return switch (outcome) {
            case Done done -> done.order();
            case Declined declined -> throw new ApiException(ErrorCode.PAYMENT_DECLINED,
                    "Payment for order " + declined.order().id() + " was declined");
            case InvalidState invalid -> throw new ApiException(ErrorCode.INVALID_STATE, invalid.detail());
        };
    }

    private void confirmPayment(Order order, String paymentId) {
        for (OrderItem item : order.itemsInLockOrder()) {
            lockProduct(item.getProductId()).confirmSale(item.getQuantity());
        }
        order.markPaid(paymentId, Times.now(clock));
    }

    // ---- 취소·환불 (R7) ----

    public OrderResponse cancel(long orderId) {
        Outcome outcome = withGateway(() -> tx.execute(status -> {
            Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> notFound(orderId));
            InvalidState invalid = expireIfDeadlinePassed(order);
            if (invalid != null) {
                return invalid;
            }
            switch (order.getStatus()) {
                case PENDING_PAYMENT -> closeUnpaid(order, OrderStatus.CANCELLED);
                case PAID -> {
                    if (order.getPaymentId() != null) {
                        paymentGateway.refund(order.getPaymentId());
                    }
                    for (OrderItem item : order.itemsInLockOrder()) {
                        lockProduct(item.getProductId()).restock(item.getQuantity());
                    }
                    releaseCoupon(order);
                    order.changeStatus(OrderStatus.REFUNDED);
                }
                default -> {
                    return invalidState(order);
                }
            }
            return new Done(OrderResponse.from(order));
        }));
        return switch (outcome) {
            case Done done -> done.order();
            case InvalidState invalid -> throw new ApiException(ErrorCode.INVALID_STATE, invalid.detail());
            case Declined declined -> throw new IllegalStateException("unreachable");
        };
    }

    // ---- 배송 (R8) ----

    public OrderResponse ship(long orderId) {
        return transition(orderId, OrderStatus.PAID, OrderStatus.SHIPPED);
    }

    public OrderResponse deliver(long orderId) {
        return transition(orderId, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    private OrderResponse transition(long orderId, OrderStatus from, OrderStatus to) {
        return tx.execute(status -> {
            Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> notFound(orderId));
            if (order.getStatus() != from) {
                throw new ApiException(ErrorCode.INVALID_STATE,
                        "Order " + orderId + " is " + order.getStatus() + ", expected " + from);
            }
            order.changeStatus(to);
            return OrderResponse.from(order);
        });
    }

    // ---- 만료 (R6) ----

    /** 결제 기한이 지난 결제 대기 주문을 만료시킨다. 다른 요청이 잡고 있으면 다음 기회로 미룬다. */
    public void expireIfDue(long orderId) {
        tx.executeWithoutResult(status -> orders.lockPendingSkipLocked(orderId)
                .filter(order -> order.isPaymentDeadlinePassed(Times.now(clock)))
                .ifPresent(order -> closeUnpaid(order, OrderStatus.EXPIRED)));
    }

    /**
     * 스케줄러보다 먼저 요청이 도착한 경우를 위해, 잠근 주문의 기한이 지났으면 그 자리에서 만료시킨다.
     * 만료는 커밋되어야 하므로 예외 대신 결과로 돌려준다.
     */
    private InvalidState expireIfDeadlinePassed(Order order) {
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT && order.isPaymentDeadlinePassed(Times.now(clock))) {
            closeUnpaid(order, OrderStatus.EXPIRED);
            return new InvalidState("Order " + order.getId() + " has expired");
        }
        return null;
    }

    // ---- 공통 ----

    /** 결제되지 않은 채 끝나는 주문: 예약·쿠폰 사용을 복원한다. */
    private void closeUnpaid(Order order, OrderStatus terminal) {
        for (OrderItem item : order.itemsInLockOrder()) {
            lockProduct(item.getProductId()).releaseReservation(item.getQuantity());
        }
        releaseCoupon(order);
        order.changeStatus(terminal);
    }

    private void releaseCoupon(Order order) {
        if (order.getCouponCode() != null) {
            coupons.findByCodeForUpdate(order.getCouponCode()).ifPresent(Coupon::release);
        }
    }

    private Product lockProduct(long productId) {
        return products.findByIdForUpdate(productId)
                .orElseThrow(() -> new IllegalStateException("Product " + productId + " vanished"));
    }

    private static InvalidState invalidState(Order order) {
        return new InvalidState("Order " + order.getId() + " is " + order.getStatus());
    }

    private static ApiException notFound(long orderId) {
        return new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order " + orderId + " not found");
    }

    private static <T> T withGateway(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (PaymentGatewayUnavailableException e) {
            throw new ApiException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, e.getMessage());
        }
    }
}
