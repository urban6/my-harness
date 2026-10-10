package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.Times;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PaymentGatewayClient.PaymentResult;
import com.example.order.point.PointRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.math.BigInteger;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    public record ItemCommand(long productId, long quantity) {
    }

    /** A committed 2xx response: status, serialized body (also stored as the idempotency snapshot), id. */
    public record Written(int status, String body, long orderId) {
    }

    private enum PayOutcome { OK, DECLINED, EXPIRED }

    private record PayResult(PayOutcome outcome, Written written) {
    }

    private final OrderRepository orders;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final PointRepository points;
    private final ReservationReleaser releaser;
    private final ExpiryService expiry;
    private final IdempotencyService idempotency;
    private final PaymentGatewayClient gateway;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Duration paymentTtl;

    public OrderService(OrderRepository orders, ProductRepository products, CouponRepository coupons,
            PointRepository points, ReservationReleaser releaser, ExpiryService expiry, IdempotencyService idempotency,
            PaymentGatewayClient gateway, ObjectMapper mapper, PlatformTransactionManager tm,
            @Value("${app.order-payment-ttl}") String paymentTtl) {
        this.orders = orders;
        this.products = products;
        this.coupons = coupons;
        this.points = points;
        this.releaser = releaser;
        this.expiry = expiry;
        this.idempotency = idempotency;
        this.gateway = gateway;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
        this.paymentTtl = Duration.parse(paymentTtl.trim());
    }

    // ---------------------------------------------------------------- create (R3, R2.4-R2.6)

    public Written create(String userId, List<ItemCommand> items, String couponCode, long usePoints,
            String idemKey) {
        expiry.expireDue();
        return tx.execute(s -> {
            // 404 first (C3)
            for (ItemCommand item : items) {
                if (!products.exists(item.productId())) {
                    throw ApiException.notFound("PRODUCT_NOT_FOUND", "product " + item.productId() + " not found");
                }
            }
            if (couponCode != null && coupons.find(couponCode).isEmpty()) {
                throw ApiException.notFound("COUPON_NOT_FOUND", "coupon " + couponCode + " not found");
            }

            // lock products in ascending id order (deadlock-free), then the coupon
            Map<Long, Product> locked = new TreeMap<>();
            items.stream().map(ItemCommand::productId).sorted().forEach(id -> locked.put(id, products.lock(id)));

            for (ItemCommand item : items) {
                if (locked.get(item.productId()).available() < item.quantity()) {
                    throw ApiException.conflict("INSUFFICIENT_STOCK",
                            "insufficient stock for product " + item.productId());
                }
            }

            List<Order.Item> lines = new ArrayList<>();
            long subtotal = 0;
            for (ItemCommand item : items) {
                long price = locked.get(item.productId()).price();
                lines.add(new Order.Item(item.productId(), item.quantity(), price, 0));
                subtotal = Math.addExact(subtotal, Math.multiplyExact(price, item.quantity()));
            }

            OffsetDateTime now = Times.now();
            long discount = 0;
            if (couponCode != null) {
                Coupon coupon = coupons.lock(couponCode);
                if (!coupon.isValidAt(now) || subtotal < coupon.minOrderAmount()
                        || orders.userHasActiveCouponOrder(userId, couponCode)) {
                    throw ApiException.conflict("COUPON_NOT_APPLICABLE",
                            "coupon " + couponCode + " cannot be applied to this order");
                }
                if (coupon.usedCount() >= coupon.totalQuantity()) {
                    throw ApiException.conflict("COUPON_EXHAUSTED", "coupon " + couponCode + " is exhausted");
                }
                discount = coupon.discountFor(subtotal);
            }

            long totalPrice = subtotal - discount;
            if (usePoints > totalPrice) {
                throw ApiException.conflict("POINTS_EXCEED_TOTAL",
                        "usePoints " + usePoints + " exceeds the order total " + totalPrice);
            }
            if (usePoints > 0 && points.lockBalance(userId) < usePoints) {
                throw ApiException.conflict("INSUFFICIENT_POINTS", "not enough points for " + usePoints);
            }

            locked.forEach((id, p) -> products.addReserved(id, quantityOf(items, id)));
            if (couponCode != null) {
                coupons.addUsed(couponCode, 1);
            }
            if (usePoints > 0) {
                points.deduct(userId, usePoints);
            }
            long id = orders.insert(userId, couponCode, subtotal, discount, totalPrice, usePoints, now,
                    Times.normalize(now.plus(paymentTtl)), lines);
            String body = json(orders.find(id).orElseThrow());
            idempotency.complete(IdempotencyService.SCOPE_CREATE_ORDER, idemKey, 201, body, location(id));
            return new Written(201, body, id);
        });
    }

    private static long quantityOf(List<ItemCommand> items, long productId) {
        return items.stream().filter(i -> i.productId() == productId).mapToLong(ItemCommand::quantity).sum();
    }

    public static String location(long id) {
        return "/api/orders/" + id;
    }

    // ---------------------------------------------------------------- pay (R5)

    /**
     * The order row stays locked (SELECT ... FOR UPDATE) for the whole PG call, so concurrent payments of the same
     * order are serialized and at most one reaches the PG (R10.5). PG failure throws 503 and rolls back: no change.
     */
    public Written pay(long orderId, String cardToken, String idemKey) {
        PayResult result = tx.execute(s -> {
            Order order = orders.lock(orderId).orElseThrow(() -> orderNotFound(orderId));
            if (order.status() != OrderStatus.PENDING_PAYMENT) {
                throw ApiException.invalidState("order " + orderId + " is " + order.status());
            }
            if (!Times.now().isBefore(order.expiresAt())) {
                expiry.expire(order);
                return new PayResult(PayOutcome.EXPIRED, null);
            }
            String paymentId = null;
            if (order.cardAmount() > 0) { // P3.2: nothing to charge the card -> approve without the PG
                PaymentResult pg = gateway.pay(order.id(), order.cardAmount(), cardToken, idemKey);
                if (pg.status() == PaymentGatewayClient.PaymentStatus.DECLINED) {
                    releaser.releaseReservation(order);
                    orders.updateStatus(order.id(), OrderStatus.PAYMENT_FAILED);
                    return new PayResult(PayOutcome.DECLINED, null);
                }
                paymentId = pg.paymentId();
            }
            releaser.consumeReservation(order);
            orders.markPaid(order.id(), Times.now(), paymentId);
            String body = json(orders.find(order.id()).orElseThrow());
            idempotency.complete(IdempotencyService.SCOPE_PAY, idemKey, 200, body, null);
            return new PayResult(PayOutcome.OK, new Written(200, body, order.id()));
        });
        return switch (result.outcome()) {
            case OK -> result.written();
            case DECLINED -> throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED",
                    "payment for order " + orderId + " was declined");
            case EXPIRED -> throw ApiException.invalidState("order " + orderId + " has expired");
        };
    }

    // ---------------------------------------------------------------- cancel (R7, P5.1) / partial refund (P4)

    public String cancel(long orderId) {
        Object result = tx.execute(s -> {
            Order order = orders.lock(orderId).orElseThrow(() -> orderNotFound(orderId));
            switch (order.status()) {
                case PENDING_PAYMENT -> {
                    if (!Times.now().isBefore(order.expiresAt())) {
                        expiry.expire(order);
                        return Boolean.FALSE;
                    }
                    releaser.releaseReservation(order);
                    orders.updateStatus(order.id(), OrderStatus.CANCELLED);
                }
                case PAID, PARTIALLY_REFUNDED -> {
                    Map<Long, Long> remaining = new TreeMap<>();
                    order.items().stream().filter(i -> i.remainingQuantity() > 0)
                            .forEach(i -> remaining.put(i.productId(), i.remainingQuantity()));
                    refund(order, remaining, "cancel-" + order.id());
                }
                default -> throw ApiException.invalidState("order " + orderId + " is " + order.status());
            }
            return json(orders.find(order.id()).orElseThrow());
        });
        if (Boolean.FALSE.equals(result)) {
            throw ApiException.invalidState("order " + orderId + " has expired");
        }
        return (String) result;
    }

    /**
     * P4: refund the given quantities. Like pay, the order row stays locked across the PG call, so concurrent
     * refunds/cancels of one order are serialized and each sees the quantities left by the previous one. A PG failure
     * throws 503 and rolls everything back.
     */
    public Written refund(long orderId, List<ItemCommand> requested, String idemKey) {
        return tx.execute(s -> {
            Order order = orders.lock(orderId).orElseThrow(() -> orderNotFound(orderId));
            if (order.status() != OrderStatus.PAID && order.status() != OrderStatus.PARTIALLY_REFUNDED) {
                throw ApiException.invalidState("order " + orderId + " is " + order.status());
            }
            Map<Long, Long> quantities = new TreeMap<>();
            for (ItemCommand item : requested) {
                Order.Item line = order.items().stream().filter(i -> i.productId() == item.productId())
                        .findFirst().orElseThrow(() -> ApiException.conflict("REFUND_QUANTITY_EXCEEDED",
                                "product " + item.productId() + " is not part of order " + orderId));
                if (item.quantity() > line.remainingQuantity()) {
                    throw ApiException.conflict("REFUND_QUANTITY_EXCEEDED", "only " + line.remainingQuantity()
                            + " of product " + item.productId() + " can still be refunded");
                }
                quantities.put(item.productId(), item.quantity());
            }
            refund(order, quantities, idemKey);
            String body = json(orders.find(order.id()).orElseThrow());
            idempotency.complete(IdempotencyService.SCOPE_REFUND, idemKey, 200, body, null);
            return new Written(200, body, order.id());
        });
    }

    /**
     * Books a refund of {@code quantities} (already validated) on a locked PAID / PARTIALLY_REFUNDED order (P4.5-P4.9).
     * The cumulative refund is prorated on list price and floored, so refunding every item yields exactly totalPrice;
     * this refund is the difference to what was refunded before. The card gets its share first, the rest is points.
     */
    private void refund(Order order, Map<Long, Long> quantities, String pgIdempotencyKey) {
        long refundedList = 0; // G after this refund: sum of unitPrice * refundedQuantity
        boolean allRefunded = true;
        for (Order.Item item : order.items()) {
            long refundedAfter = item.refundedQuantity() + quantities.getOrDefault(item.productId(), 0L);
            refundedList = Math.addExact(refundedList, Math.multiplyExact(item.unitPrice(), refundedAfter));
            allRefunded &= refundedAfter == item.quantity();
        }
        long cumulative = BigInteger.valueOf(order.totalPrice()).multiply(BigInteger.valueOf(refundedList))
                .divide(BigInteger.valueOf(order.subtotal())).longValueExact();
        long amount = cumulative - order.refundedAmount();
        long cardRefund = Math.min(amount, order.cardAmount() - order.cardRefundedAmount());
        long pointRefund = amount - cardRefund;

        if (cardRefund > 0 && order.paymentId() != null) {
            gateway.refund(order.paymentId(), cardRefund, pgIdempotencyKey);
        }

        quantities.forEach((productId, qty) -> {
            if (qty > 0) {
                orders.addRefundedQuantity(order.id(), productId, qty);
            }
        });
        releaser.returnStock(order, quantities, allRefunded);
        releaser.returnPoints(order, pointRefund);
        orders.applyRefund(order.id(), amount, cardRefund,
                allRefunded ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
    }

    // ---------------------------------------------------------------- shipping (R8)

    public String ship(long orderId) {
        return transition(orderId, EnumSet.of(OrderStatus.PAID, OrderStatus.PARTIALLY_REFUNDED),
                OrderStatus.SHIPPED);
    }

    public String deliver(long orderId) {
        return transition(orderId, EnumSet.of(OrderStatus.SHIPPED), OrderStatus.DELIVERED);
    }

    private String transition(long orderId, Set<OrderStatus> from, OrderStatus to) {
        expiry.expireDue();
        return tx.execute(s -> {
            Order order = orders.lock(orderId).orElseThrow(() -> orderNotFound(orderId));
            if (!from.contains(order.status())) {
                throw ApiException.invalidState("order " + orderId + " is " + order.status());
            }
            orders.updateStatus(order.id(), to);
            return json(orders.find(order.id()).orElseThrow());
        });
    }

    // ---------------------------------------------------------------- reads (R3.5, R9)

    public OrderResponse get(long orderId) {
        expiry.expireDue();
        return orders.find(orderId).map(OrderResponse::from).orElseThrow(() -> orderNotFound(orderId));
    }

    public record Page(List<OrderResponse> content, String nextCursor) {
    }

    public Page list(String userId, OrderStatus status, int size, OrderCursor cursor) {
        expiry.expireDue();
        List<Order> rows = orders.page(userId, status, cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(), size + 1);
        boolean more = rows.size() > size;
        List<Order> pageRows = more ? rows.subList(0, size) : rows;
        String next = null;
        if (more) {
            Order last = pageRows.get(pageRows.size() - 1);
            next = new OrderCursor(last.createdAt(), last.id()).encode();
        }
        return new Page(pageRows.stream().map(OrderResponse::from).toList(), next);
    }

    private static ApiException orderNotFound(long id) {
        return ApiException.notFound("ORDER_NOT_FOUND", "order " + id + " not found");
    }

    private String json(Order order) {
        try {
            return mapper.writeValueAsString(OrderResponse.from(order));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
