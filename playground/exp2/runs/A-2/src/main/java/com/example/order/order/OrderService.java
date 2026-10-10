package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.Times;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PaymentGatewayClient.PaymentResult;
import com.example.order.payment.PaymentGatewayClient.PaymentStatus;
import com.example.order.payment.PaymentGatewayUnavailableException;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 상태 전이와 재고·쿠폰 반영.
 * 잠금 순서는 항상 주문 → 상품(id 오름차순) → 쿠폰이다.
 * PG 호출은 트랜잭션 밖에서 하고, 그동안 주문에 pending operation을 표시해 다른 전이를 막는다.
 */
@Service
public class OrderService {

    static final Set<OrderStatus> COUPON_IN_USE = EnumSet.of(
            OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED);

    public record LineRequest(long productId, int quantity) {
    }

    public record Page(List<OrderResponse> content, String nextCursor) {
    }

    private final OrderRepository orders;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final PaymentGatewayClient paymentGateway;
    private final TransactionTemplate tx;
    private final EntityManager em;
    private final Duration paymentTtl;

    public OrderService(OrderRepository orders, ProductRepository products, CouponRepository coupons,
                        PaymentGatewayClient paymentGateway, TransactionTemplate tx, EntityManager em,
                        @Value("${order.payment-ttl}") Duration paymentTtl) {
        this.orders = orders;
        this.products = products;
        this.coupons = coupons;
        this.paymentGateway = paymentGateway;
        this.tx = tx;
        this.em = em;
        this.paymentTtl = paymentTtl;
    }

    // ---------------------------------------------------------------- 생성·조회

    public OrderResponse create(String userId, List<LineRequest> lines, String couponCode) {
        return tx.execute(status -> {
            Instant now = Times.now();
            Map<Long, Product> locked = lockProducts(lines.stream().map(LineRequest::productId).toList());
            for (LineRequest line : lines) {
                if (!locked.containsKey(line.productId())) {
                    throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product " + line.productId() + " not found");
                }
            }
            Coupon coupon = couponCode == null ? null : coupons.findForUpdate(couponCode)
                    .orElseThrow(() -> ApiException.notFound("COUPON_NOT_FOUND", "Coupon " + couponCode + " not found"));

            for (LineRequest line : lines) {
                if (locked.get(line.productId()).available() < line.quantity()) {
                    throw ApiException.conflict("INSUFFICIENT_STOCK",
                            "Insufficient stock for product " + line.productId());
                }
            }
            List<OrderLine> orderLines = lines.stream()
                    .map(l -> new OrderLine(l.productId(), l.quantity(), locked.get(l.productId()).getPrice()))
                    .toList();
            long subtotal = orderLines.stream()
                    .mapToLong(l -> Math.multiplyExact(l.getUnitPrice(), (long) l.getQuantity()))
                    .reduce(0L, Math::addExact);

            long discount = 0;
            if (coupon != null) {
                if (!coupon.isValidAt(now) || subtotal < coupon.getMinOrderAmount()
                        || orders.existsCouponUsage(userId, coupon.getCode(), COUPON_IN_USE)) {
                    throw ApiException.conflict("COUPON_NOT_APPLICABLE",
                            "Coupon " + coupon.getCode() + " is not applicable to this order");
                }
                if (coupon.isExhausted()) {
                    throw ApiException.conflict("COUPON_EXHAUSTED", "Coupon " + coupon.getCode() + " is exhausted");
                }
                discount = coupon.discountFor(subtotal);
                coupon.use();
            }
            for (LineRequest line : lines) {
                locked.get(line.productId()).reserve(line.quantity());
            }
            Order order = orders.save(new Order(userId, orderLines, couponCode, subtotal, discount,
                    now, now.plus(paymentTtl)));
            return OrderResponse.of(order);
        });
    }

    public OrderResponse get(long id) {
        return tx.execute(status -> OrderResponse.of(orders.findById(id).orElseThrow(() -> notFound(id))));
    }

    public Page list(String userId, OrderStatus status, int size, OrderCursor cursor) {
        return tx.execute(txStatus -> {
            StringBuilder jpql = new StringBuilder("select o from Order o where 1 = 1");
            if (userId != null) {
                jpql.append(" and o.userId = :userId");
            }
            if (status != null) {
                jpql.append(" and o.status = :status");
            }
            if (cursor != null) {
                jpql.append(" and (o.createdAt < :cursorAt or (o.createdAt = :cursorAt and o.id < :cursorId))");
            }
            jpql.append(" order by o.createdAt desc, o.id desc");
            TypedQuery<Order> query = em.createQuery(jpql.toString(), Order.class).setMaxResults(size + 1);
            if (userId != null) {
                query.setParameter("userId", userId);
            }
            if (status != null) {
                query.setParameter("status", status);
            }
            if (cursor != null) {
                query.setParameter("cursorAt", cursor.createdAt());
                query.setParameter("cursorId", cursor.id());
            }
            List<Order> found = query.getResultList();
            List<Order> page = found.subList(0, Math.min(size, found.size()));
            String next = found.size() > size ? OrderCursor.of(page.get(page.size() - 1)).encode() : null;
            return new Page(page.stream().map(OrderResponse::of).toList(), next);
        });
    }

    // ---------------------------------------------------------------- 결제

    private record PayStart(OrderResponse completed, long amount) {
    }

    public OrderResponse pay(long id, String idempotencyKey, String cardToken) {
        PayStart start = tx.execute(status -> {
            Order order = lockOrder(id);
            Instant now = Times.now();
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.isExpiredAt(now)
                    || order.hasPendingOperation(now)) {
                throw ApiException.invalidState("Order " + id + " cannot be paid in its current state");
            }
            if (order.getTotalPrice() == 0) {
                completePayment(order, null, now);
                return new PayStart(OrderResponse.of(order), 0);
            }
            order.startOperation("PAYING", now);
            return new PayStart(null, order.getTotalPrice());
        });
        if (start.completed() != null) {
            return start.completed();
        }

        PaymentResult result;
        try {
            result = paymentGateway.pay(idempotencyKey, id, start.amount(), cardToken);
        } catch (PaymentGatewayUnavailableException e) {
            tx.executeWithoutResult(status -> lockOrder(id).finishOperation());
            throw ApiException.gatewayUnavailable("Payment gateway is unavailable");
        }

        if (result.status() == PaymentStatus.APPROVED) {
            return tx.execute(status -> {
                Order order = lockOrder(id);
                order.finishOperation();
                completePayment(order, result.paymentId(), Times.now());
                return OrderResponse.of(order);
            });
        }
        tx.executeWithoutResult(status -> {
            Order order = lockOrder(id);
            order.finishOperation();
            order.changeStatus(OrderStatus.PAYMENT_FAILED);
            releaseReservation(order);
        });
        throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "Payment was declined");
    }

    private void completePayment(Order order, String paymentId, Instant now) {
        Map<Long, Product> locked = lockProducts(productIds(order));
        order.getItems().forEach(l -> locked.get(l.getProductId()).sellReserved(l.getQuantity()));
        order.markPaid(paymentId, now);
    }

    // ---------------------------------------------------------------- 취소·환불

    private record CancelStart(OrderResponse completed, String paymentId) {
    }

    public OrderResponse cancel(long id) {
        CancelStart start = tx.execute(status -> {
            Order order = lockOrder(id);
            Instant now = Times.now();
            if (order.hasPendingOperation(now)) {
                throw ApiException.invalidState("Order " + id + " is being processed");
            }
            switch (order.getStatus()) {
                case PENDING_PAYMENT -> {
                    order.changeStatus(OrderStatus.CANCELLED);
                    releaseReservation(order);
                    return new CancelStart(OrderResponse.of(order), null);
                }
                case PAID -> {
                    if (order.getPaymentId() == null) { // 0원 주문은 PG를 거치지 않았다
                        completeRefund(order);
                        return new CancelStart(OrderResponse.of(order), null);
                    }
                    order.startOperation("REFUNDING", now);
                    return new CancelStart(null, order.getPaymentId());
                }
                default -> throw ApiException.invalidState("Order " + id + " cannot be cancelled in status "
                        + order.getStatus());
            }
        });
        if (start.completed() != null) {
            return start.completed();
        }

        try {
            paymentGateway.refund(start.paymentId());
        } catch (PaymentGatewayUnavailableException e) {
            tx.executeWithoutResult(status -> lockOrder(id).finishOperation());
            throw ApiException.gatewayUnavailable("Payment gateway is unavailable");
        }
        return tx.execute(status -> {
            Order order = lockOrder(id);
            order.finishOperation();
            completeRefund(order);
            return OrderResponse.of(order);
        });
    }

    private void completeRefund(Order order) {
        Map<Long, Product> locked = lockProducts(productIds(order));
        order.getItems().forEach(l -> locked.get(l.getProductId()).restock(l.getQuantity()));
        releaseCoupon(order);
        order.changeStatus(OrderStatus.REFUNDED);
    }

    // ---------------------------------------------------------------- 배송·만료

    public OrderResponse ship(long id) {
        return transition(id, OrderStatus.PAID, OrderStatus.SHIPPED);
    }

    public OrderResponse deliver(long id) {
        return transition(id, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    private OrderResponse transition(long id, OrderStatus from, OrderStatus to) {
        return tx.execute(status -> {
            Order order = lockOrder(id);
            if (order.getStatus() != from || order.hasPendingOperation(Times.now())) {
                throw ApiException.invalidState("Order " + id + " cannot move from " + order.getStatus() + " to " + to);
            }
            order.changeStatus(to);
            return OrderResponse.of(order);
        });
    }

    /** 결제 대기 시간이 지난 주문들을 EXPIRED로 바꾸고 예약·쿠폰 사용을 복원한다. */
    public int expireDueOrders() {
        int expired = 0;
        for (Long id : orders.findExpiredPendingIds(Times.now())) {
            Boolean done = tx.execute(status -> {
                Order order = orders.findForUpdate(id).orElse(null);
                Instant now = Times.now();
                if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT
                        || !order.isExpiredAt(now) || order.hasPendingOperation(now)) {
                    return false;
                }
                order.changeStatus(OrderStatus.EXPIRED);
                releaseReservation(order);
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                expired++;
            }
        }
        return expired;
    }

    // ---------------------------------------------------------------- 공통

    private void releaseReservation(Order order) {
        Map<Long, Product> locked = lockProducts(productIds(order));
        order.getItems().forEach(l -> locked.get(l.getProductId()).releaseReservation(l.getQuantity()));
        releaseCoupon(order);
    }

    private void releaseCoupon(Order order) {
        if (order.getCouponCode() != null) {
            coupons.findForUpdate(order.getCouponCode()).ifPresent(Coupon::release);
        }
    }

    private Map<Long, Product> lockProducts(List<Long> ids) {
        return products.findAllForUpdate(ids).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private static List<Long> productIds(Order order) {
        return order.getItems().stream().map(OrderLine::getProductId).toList();
    }

    private Order lockOrder(long id) {
        return orders.findForUpdate(id).orElseThrow(() -> notFound(id));
    }

    private static ApiException notFound(long id) {
        return ApiException.notFound("ORDER_NOT_FOUND", "Order " + id + " not found");
    }
}
