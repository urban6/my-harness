package com.example.order.service;

import com.example.order.domain.*;
import com.example.order.repo.*;
import com.example.order.web.ApiException;
import com.example.order.web.Dtos.*;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Order lifecycle. Each public operation runs in its own transaction (TransactionTemplate) so that
 * lazy expiry can be committed before a business error is reported, and so the external PG call
 * happens while the order row is locked (one payment attempt at a time per order).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orders;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final IdempotencyRepository idempotency;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;
    private final Duration paymentTtl;

    public OrderService(OrderRepository orders, ProductRepository products, CouponRepository coupons,
                        IdempotencyRepository idempotency, PaymentGateway gateway,
                        PlatformTransactionManager txManager, Clock clock,
                        @Value("${order.payment-ttl}") Duration paymentTtl) {
        this.orders = orders;
        this.products = products;
        this.coupons = coupons;
        this.idempotency = idempotency;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
        this.paymentTtl = paymentTtl;
    }

    // ---- create -------------------------------------------------------------------------------

    public OrderResponse create(String userId, String idemKey, CreateOrderRequest req) {
        Map<Long, Integer> items = new TreeMap<>(); // sorted => consistent row lock order
        for (OrderItemRequest i : req.items()) {
            items.merge(i.productId(), i.quantity(), Integer::sum);
        }
        String coupon = req.couponCode() == null || req.couponCode().isBlank() ? null : req.couponCode();
        String fingerprint = items + "|" + coupon;
        try {
            return tx.execute(s -> doCreate(userId, idemKey, fingerprint, items, coupon));
        } catch (DataIntegrityViolationException race) {
            // a concurrent request with the same key committed first; replay its result
            return tx.execute(s -> idempotency.findByUserIdAndIdemKey(userId, idemKey)
                    .map(r -> replay(r, fingerprint))
                    .orElseThrow(() -> race));
        }
    }

    private OrderResponse doCreate(String userId, String idemKey, String fingerprint, Map<Long, Integer> items, String couponCode) {
        Optional<IdempotencyRecord> existing = idempotency.findByUserIdAndIdemKey(userId, idemKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        // Claim the key first: a concurrent duplicate blocks here and then fails on the unique index.
        IdempotencyRecord record = idempotency.saveAndFlush(new IdempotencyRecord(userId, idemKey, fingerprint));

        Map<Long, Product> found = new HashMap<>();
        products.findAllById(items.keySet()).forEach(p -> found.put(p.getId(), p));
        for (Long id : items.keySet()) {
            if (!found.containsKey(id)) {
                throw ApiException.notFound("Product", id);
            }
        }
        long subtotal = 0;
        for (var e : items.entrySet()) {
            Product p = found.get(e.getKey());
            if (products.reserve(p.getId(), e.getValue()) == 0) {
                throw ApiException.conflict("Insufficient Stock", "Not enough stock for product " + p.getId());
            }
            subtotal += p.getPrice() * e.getValue();
        }

        long discount = 0;
        if (couponCode != null) {
            Coupon c = coupons.findByCode(couponCode).orElseThrow(() -> ApiException.notFound("Coupon", couponCode));
            Instant now = clock.instant();
            if (now.isBefore(c.getValidFrom()) || now.isAfter(c.getValidUntil())) {
                throw ApiException.unprocessable("Coupon Not Applicable", "Coupon is outside its validity period");
            }
            if (subtotal < c.getMinOrderAmount()) {
                throw ApiException.unprocessable("Coupon Not Applicable", "Order amount is below the coupon minimum");
            }
            if (coupons.use(couponCode) == 0) {
                throw ApiException.conflict("Coupon Exhausted", "Coupon has no remaining quantity");
            }
            discount = c.discountFor(subtotal);
        }

        Instant now = clock.instant();
        Order order = new Order(userId, couponCode, subtotal, discount, now, now.plus(paymentTtl));
        for (var e : items.entrySet()) {
            order.addItem(e.getKey(), e.getValue(), found.get(e.getKey()).getPrice());
        }
        orders.save(order);
        record.setOrderId(order.getId());
        return OrderResponse.of(order);
    }

    private OrderResponse replay(IdempotencyRecord record, String fingerprint) {
        if (!record.getFingerprint().equals(fingerprint)) {
            throw ApiException.unprocessable("Idempotency Key Reuse", "Idempotency-Key was already used with a different request");
        }
        return OrderResponse.of(orders.findById(record.getOrderId()).orElseThrow());
    }

    // ---- read ---------------------------------------------------------------------------------

    public OrderResponse get(Long id) {
        expireIfDue(id);
        return readTx.execute(s -> OrderResponse.of(orders.findById(id).orElseThrow(() -> ApiException.notFound("Order", id))));
    }

    public OrderPage list(String userId, OrderStatus status, Integer size, Long cursor) {
        int limit = size == null ? DEFAULT_PAGE_SIZE : size;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Bad Request",
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        expireDue();
        Specification<Order> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (userId != null) ps.add(cb.equal(root.get("userId"), userId));
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (cursor != null) ps.add(cb.lessThan(root.get("id"), cursor));
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return readTx.execute(s -> {
            List<Order> rows = orders.findBy(spec, q -> q.sortBy(Sort.by(Sort.Direction.DESC, "id")).limit(limit + 1).all());
            boolean more = rows.size() > limit;
            List<Order> page = more ? rows.subList(0, limit) : rows;
            String next = more ? String.valueOf(page.get(page.size() - 1).getId()) : null;
            return new OrderPage(page.stream().map(OrderResponse::of).toList(), next);
        });
    }

    // ---- pay ----------------------------------------------------------------------------------

    public OrderResponse pay(Long id, String idemKey, String cardToken) {
        expireIfDue(id);
        return tx.execute(s -> {
            Order o = lock(id);
            if (o.getStatus() != OrderStatus.PENDING_PAYMENT) {
                if (idemKey.equals(o.getPaymentKey())) {
                    return OrderResponse.of(o); // replay of an already-processed payment
                }
                throw invalidState(o, "pay");
            }
            if (!o.getExpiresAt().isAfter(clock.instant())) {
                throw ApiException.conflict("Order Expired", "Order payment window has passed");
            }
            PaymentGateway.Payment p = gateway.charge(o.getId(), o.getTotalPrice(), cardToken, idemKey);
            o.setPaymentKey(idemKey);
            o.setPaymentId(p.paymentId());
            if (p.approved()) {
                for (OrderItem i : o.itemsInLockOrder()) {
                    products.commit(i.getProductId(), i.getQuantity());
                }
                o.setStatus(OrderStatus.PAID);
                o.setPaidAt(clock.instant());
            } else {
                releaseHolds(o);
                o.setStatus(OrderStatus.PAYMENT_FAILED);
            }
            return OrderResponse.of(o);
        });
    }

    // ---- cancel / ship / deliver --------------------------------------------------------------

    public OrderResponse cancel(Long id) {
        expireIfDue(id);
        return tx.execute(s -> {
            Order o = lock(id);
            switch (o.getStatus()) {
                case PENDING_PAYMENT -> {
                    releaseHolds(o);
                    o.setStatus(OrderStatus.CANCELLED);
                }
                case PAID -> {
                    gateway.refund(o.getPaymentId());
                    for (OrderItem i : o.itemsInLockOrder()) {
                        products.restock(i.getProductId(), i.getQuantity());
                    }
                    releaseCoupon(o);
                    o.setStatus(OrderStatus.REFUNDED);
                }
                default -> throw invalidState(o, "cancel");
            }
            return OrderResponse.of(o);
        });
    }

    public OrderResponse ship(Long id) {
        return transition(id, OrderStatus.PAID, OrderStatus.SHIPPED, "ship");
    }

    public OrderResponse deliver(Long id) {
        return transition(id, OrderStatus.SHIPPED, OrderStatus.DELIVERED, "deliver");
    }

    private OrderResponse transition(Long id, OrderStatus from, OrderStatus to, String action) {
        expireIfDue(id);
        return tx.execute(s -> {
            Order o = lock(id);
            if (o.getStatus() != from) {
                throw invalidState(o, action);
            }
            o.setStatus(to);
            return OrderResponse.of(o);
        });
    }

    // ---- expiry -------------------------------------------------------------------------------

    @Scheduled(fixedDelay = 5000)
    public void expireDue() {
        for (Long id : orders.findExpiredPendingIds(clock.instant())) {
            try {
                expireIfDue(id);
            } catch (RuntimeException e) {
                log.warn("Failed to expire order {}", id, e);
            }
        }
    }

    /** Expires the order if it is still awaiting payment past its deadline; no-op (and no 404) otherwise. */
    void expireIfDue(Long id) {
        tx.executeWithoutResult(s -> orders.findForUpdate(id).ifPresent(o -> {
            if (o.getStatus() == OrderStatus.PENDING_PAYMENT && !o.getExpiresAt().isAfter(clock.instant())) {
                releaseHolds(o);
                o.setStatus(OrderStatus.EXPIRED);
            }
        }));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Order lock(Long id) {
        return orders.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Order", id));
    }

    /** Give back reserved stock and the coupon use of an order that never got paid. */
    private void releaseHolds(Order o) {
        for (OrderItem i : o.itemsInLockOrder()) {
            products.release(i.getProductId(), i.getQuantity());
        }
        releaseCoupon(o);
    }

    private void releaseCoupon(Order o) {
        if (o.getCouponCode() != null) {
            coupons.release(o.getCouponCode());
        }
    }

    private static ApiException invalidState(Order o, String action) {
        return ApiException.conflict("Invalid Order State", "Cannot " + action + " an order in status " + o.getStatus());
    }
}
