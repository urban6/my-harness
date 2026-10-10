package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.Times;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PaymentGatewayClient.PaymentResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ReservationReleaser releaser;
    private final ExpiryService expiry;
    private final IdempotencyService idempotency;
    private final PaymentGatewayClient gateway;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Duration paymentTtl;

    public OrderService(OrderRepository orders, ProductRepository products, CouponRepository coupons,
            ReservationReleaser releaser, ExpiryService expiry, IdempotencyService idempotency,
            PaymentGatewayClient gateway, ObjectMapper mapper, PlatformTransactionManager tm,
            @Value("${app.order-payment-ttl}") String paymentTtl) {
        this.orders = orders;
        this.products = products;
        this.coupons = coupons;
        this.releaser = releaser;
        this.expiry = expiry;
        this.idempotency = idempotency;
        this.gateway = gateway;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
        this.paymentTtl = Duration.parse(paymentTtl.trim());
    }

    // ---------------------------------------------------------------- create (R3, R2.4-R2.6)

    /** Everything (products, coupon, the user's coupon use) is resolved within the request tenant (M2, M3.2, M4). */
    public Written create(String tenantId, String userId, List<ItemCommand> items, String couponCode,
            String idemKey) {
        expiry.expireDue();
        return tx.execute(s -> {
            // 404 first (C3); another tenant's product or coupon is "not found" (M2.3)
            for (ItemCommand item : items) {
                if (!products.exists(tenantId, item.productId())) {
                    throw ApiException.notFound("PRODUCT_NOT_FOUND", "product " + item.productId() + " not found");
                }
            }
            if (couponCode != null && coupons.find(tenantId, couponCode).isEmpty()) {
                throw ApiException.notFound("COUPON_NOT_FOUND", "coupon " + couponCode + " not found");
            }

            // lock products in ascending id order (deadlock-free), then the coupon
            Map<Long, Product> locked = new TreeMap<>();
            items.stream().map(ItemCommand::productId).sorted()
                    .forEach(id -> locked.put(id, products.lock(tenantId, id)));

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
                lines.add(new Order.Item(item.productId(), item.quantity(), price));
                subtotal = Math.addExact(subtotal, Math.multiplyExact(price, item.quantity()));
            }

            OffsetDateTime now = Times.now();
            long discount = 0;
            if (couponCode != null) {
                Coupon coupon = coupons.lock(tenantId, couponCode);
                if (!coupon.isValidAt(now) || subtotal < coupon.minOrderAmount()
                        || orders.userHasActiveCouponOrder(tenantId, userId, couponCode)) {
                    throw ApiException.conflict("COUPON_NOT_APPLICABLE",
                            "coupon " + couponCode + " cannot be applied to this order");
                }
                if (coupon.usedCount() >= coupon.totalQuantity()) {
                    throw ApiException.conflict("COUPON_EXHAUSTED", "coupon " + couponCode + " is exhausted");
                }
                discount = coupon.discountFor(subtotal);
            }

            locked.forEach((id, p) -> products.addReserved(id, quantityOf(items, id)));
            if (couponCode != null) {
                coupons.addUsed(tenantId, couponCode, 1);
            }
            long id = orders.insert(tenantId, userId, couponCode, subtotal, discount, subtotal - discount, now,
                    Times.normalize(now.plus(paymentTtl)), lines);
            String body = json(orders.find(tenantId, id).orElseThrow());
            idempotency.complete(tenantId, IdempotencyService.SCOPE_CREATE_ORDER, idemKey, 201, body, location(id));
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
     * The PG Idempotency-Key is "{tenantId}:{client key}" (M6.1).
     */
    public Written pay(String tenantId, long orderId, String cardToken, String idemKey) {
        PayResult result = tx.execute(s -> {
            Order order = orders.lock(tenantId, orderId).orElseThrow(() -> orderNotFound(orderId));
            if (order.status() != OrderStatus.PENDING_PAYMENT) {
                throw ApiException.invalidState("order " + orderId + " is " + order.status());
            }
            if (!Times.now().isBefore(order.expiresAt())) {
                expiry.expire(order);
                return new PayResult(PayOutcome.EXPIRED, null);
            }
            String paymentId = null;
            if (order.totalPrice() > 0) {
                PaymentResult pg = gateway.pay(order.id(), order.totalPrice(), cardToken,
                        pgIdempotencyKey(tenantId, idemKey));
                if (pg.status() == PaymentGatewayClient.PaymentStatus.DECLINED) {
                    releaser.releaseReservation(order);
                    orders.updateStatus(order.id(), OrderStatus.PAYMENT_FAILED);
                    return new PayResult(PayOutcome.DECLINED, null);
                }
                paymentId = pg.paymentId();
            }
            releaser.consumeReservation(order);
            orders.markPaid(order.id(), Times.now(), paymentId);
            String body = json(orders.find(tenantId, order.id()).orElseThrow());
            idempotency.complete(tenantId, IdempotencyService.SCOPE_PAY, idemKey, 200, body, null);
            return new PayResult(PayOutcome.OK, new Written(200, body, order.id()));
        });
        return switch (result.outcome()) {
            case OK -> result.written();
            case DECLINED -> throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED",
                    "payment for order " + orderId + " was declined");
            case EXPIRED -> throw ApiException.invalidState("order " + orderId + " has expired");
        };
    }

    static String pgIdempotencyKey(String tenantId, String idemKey) {
        return tenantId + ":" + idemKey;
    }

    // ---------------------------------------------------------------- cancel / refund (R7)

    public String cancel(String tenantId, long orderId) {
        Object result = tx.execute(s -> {
            Order order = orders.lock(tenantId, orderId).orElseThrow(() -> orderNotFound(orderId));
            switch (order.status()) {
                case PENDING_PAYMENT -> {
                    if (!Times.now().isBefore(order.expiresAt())) {
                        expiry.expire(order);
                        return Boolean.FALSE;
                    }
                    releaser.releaseReservation(order);
                    orders.updateStatus(order.id(), OrderStatus.CANCELLED);
                }
                case PAID -> {
                    if (order.paymentId() != null) {
                        gateway.refund(order.paymentId());
                    }
                    releaser.returnStock(order);
                    orders.updateStatus(order.id(), OrderStatus.REFUNDED);
                }
                default -> throw ApiException.invalidState("order " + orderId + " is " + order.status());
            }
            return json(orders.find(tenantId, order.id()).orElseThrow());
        });
        if (Boolean.FALSE.equals(result)) {
            throw ApiException.invalidState("order " + orderId + " has expired");
        }
        return (String) result;
    }

    // ---------------------------------------------------------------- shipping (R8)

    public String ship(String tenantId, long orderId) {
        return transition(tenantId, orderId, OrderStatus.PAID, OrderStatus.SHIPPED);
    }

    public String deliver(String tenantId, long orderId) {
        return transition(tenantId, orderId, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    private String transition(String tenantId, long orderId, OrderStatus from, OrderStatus to) {
        expiry.expireDue();
        return tx.execute(s -> {
            Order order = orders.lock(tenantId, orderId).orElseThrow(() -> orderNotFound(orderId));
            if (order.status() != from) {
                throw ApiException.invalidState("order " + orderId + " is " + order.status());
            }
            orders.updateStatus(order.id(), to);
            return json(orders.find(tenantId, order.id()).orElseThrow());
        });
    }

    // ---------------------------------------------------------------- reads (R3.5, R9)

    public OrderResponse get(String tenantId, long orderId) {
        expiry.expireDue();
        return orders.find(tenantId, orderId).map(OrderResponse::from).orElseThrow(() -> orderNotFound(orderId));
    }

    public record Page(List<OrderResponse> content, String nextCursor) {
    }

    /** M7.1: only the request tenant's orders; the userId filter applies within that tenant. */
    public Page list(String tenantId, String userId, OrderStatus status, int size, OrderCursor cursor) {
        expiry.expireDue();
        List<Order> rows = orders.page(tenantId, userId, status, cursor == null ? null : cursor.createdAt(),
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
