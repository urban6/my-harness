package com.example.order.service;

import com.example.order.config.AppProperties;
import com.example.order.domain.Coupon;
import com.example.order.domain.Order;
import com.example.order.domain.OrderItem;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.Product;
import com.example.order.gateway.PaymentGateway;
import com.example.order.gateway.PaymentGateway.PaymentResult;
import com.example.order.gateway.PaymentGatewayException;
import com.example.order.repository.CouponRepository;
import com.example.order.repository.OrderRepository;
import com.example.order.repository.ProductRepository;
import com.example.order.web.ApiException;
import com.example.order.web.Dtos.OrderItemRequest;
import com.example.order.web.Dtos.OrderPage;
import com.example.order.web.Dtos.OrderRequest;
import com.example.order.web.Dtos.OrderResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Order lifecycle. Gateway calls never run inside a DB transaction: an order is first "claimed"
 * (paymentStartedAt set, under a row lock), the gateway is called, then the verdict is applied
 * in a second short transaction. Stock and coupon counters are only changed with guarded UPDATEs.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orders;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final PaymentGateway gateway;
    private final AppProperties props;
    private final Clock clock;
    private final EntityManager em;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orders, ProductRepository products, CouponRepository coupons,
                        PaymentGateway gateway, AppProperties props, Clock clock, EntityManager em,
                        PlatformTransactionManager txManager) {
        this.orders = orders;
        this.products = products;
        this.coupons = coupons;
        this.gateway = gateway;
        this.props = props;
        this.clock = clock;
        this.em = em;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---------------------------------------------------------------- create

    public OrderResponse create(String userId, String idempotencyKey, OrderRequest req) {
        Map<Long, Integer> quantities = new TreeMap<>();
        for (OrderItemRequest item : req.items()) {
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        }
        String couponCode = req.couponCode() == null || req.couponCode().isBlank() ? null : req.couponCode().trim();
        String hash = quantities + "|" + couponCode;

        return tx.execute(status -> {
            // serialises concurrent requests carrying the same key (released at commit)
            em.createNativeQuery("select 1 from (select pg_advisory_xact_lock(hashtext(?1))) t")
                    .setParameter(1, userId.length() + ":" + userId + idempotencyKey).getSingleResult();
            Order existing = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElse(null);
            if (existing != null) {
                if (!existing.getRequestHash().equals(hash)) {
                    throw ApiException.unprocessable("Idempotency-Key was already used with a different request");
                }
                return OrderResponse.from(existing);
            }

            for (Map.Entry<Long, Integer> e : quantities.entrySet()) { // ascending id: no lock-order deadlocks
                if (products.reserve(e.getKey(), e.getValue()) == 0) {
                    if (!products.existsById(e.getKey())) {
                        throw ApiException.notFound("product", e.getKey());
                    }
                    throw ApiException.conflict("insufficient stock for product " + e.getKey());
                }
            }
            Map<Long, Product> byId = new TreeMap<>();
            products.findAllById(quantities.keySet()).forEach(p -> byId.put(p.getId(), p));
            long subtotal = 0;
            for (Map.Entry<Long, Integer> e : quantities.entrySet()) {
                subtotal += byId.get(e.getKey()).getPrice() * e.getValue();
            }

            Instant now = clock.instant();
            long discount = 0;
            if (couponCode != null) {
                Coupon coupon = coupons.findById(couponCode)
                        .orElseThrow(() -> ApiException.unprocessable("coupon " + couponCode + " does not exist"));
                if (!coupon.isValidAt(now)) {
                    throw ApiException.unprocessable("coupon " + couponCode + " is not valid at this time");
                }
                if (subtotal < coupon.getMinOrderAmount()) {
                    throw ApiException.unprocessable("order amount is below the coupon minimum of " + coupon.getMinOrderAmount());
                }
                discount = coupon.discountFor(subtotal);
                if (coupons.use(couponCode) == 0) {
                    throw ApiException.conflict("coupon " + couponCode + " is exhausted");
                }
            }

            Order order = new Order(userId, idempotencyKey, hash, couponCode, subtotal, discount,
                    now, now.plus(props.orderPaymentTtl()));
            quantities.forEach((pid, qty) -> order.addItem(pid, qty, byId.get(pid).getPrice()));
            orders.saveAndFlush(order);
            return OrderResponse.from(order);
        });
    }

    // ------------------------------------------------------------------ read

    public OrderResponse get(long id) {
        return tx.execute(s -> {
            expireIfDueLocked(id);
            return OrderResponse.from(orders.findById(id).orElseThrow(() -> ApiException.notFound("order", id)));
        });
    }

    public OrderPage list(String userId, OrderStatus status, int size, String cursor) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                cursorId = Long.parseLong(cursor);
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("invalid cursor");
            }
        }
        expireDue();
        Long cursorFinal = cursorId;
        return tx.execute(s -> {
            StringBuilder jpql = new StringBuilder("select o from Order o where 1 = 1");
            if (userId != null) jpql.append(" and o.userId = :userId");
            if (status != null) jpql.append(" and o.status = :status");
            if (cursorFinal != null) jpql.append(" and o.id < :cursor");
            jpql.append(" order by o.id desc");
            TypedQuery<Order> q = em.createQuery(jpql.toString(), Order.class);
            if (userId != null) q.setParameter("userId", userId);
            if (status != null) q.setParameter("status", status);
            if (cursorFinal != null) q.setParameter("cursor", cursorFinal);
            List<Order> rows = q.setMaxResults(size + 1).getResultList();
            boolean hasMore = rows.size() > size;
            List<Order> page = hasMore ? rows.subList(0, size) : rows;
            String next = hasMore ? String.valueOf(page.get(page.size() - 1).getId()) : null;
            return new OrderPage(page.stream().map(OrderResponse::from).toList(), next);
        });
    }

    // ------------------------------------------------------------------- pay

    private record PayClaim(OrderResponse replay, long amount) {
    }

    public OrderResponse pay(long id, String idempotencyKey, String cardToken) {
        PayClaim claim = tx.execute(s -> {
            expireIfDueLocked(id);
            Order o = lock(id);
            Instant now = clock.instant();
            boolean sameKey = idempotencyKey.equals(o.getPayIdempotencyKey());
            if (o.getStatus() != OrderStatus.PENDING_PAYMENT) {
                if (sameKey) {
                    return new PayClaim(OrderResponse.from(o), 0);
                }
                throw ApiException.conflict("order " + id + " is " + o.getStatus() + " and cannot be paid");
            }
            if (o.isProcessing(now, props.paymentLockTimeout())) {
                throw ApiException.conflict("a payment for order " + id + " is already in progress");
            }
            o.beginProcessing(idempotencyKey, now);
            return new PayClaim(null, o.getTotalPrice());
        });
        if (claim.replay() != null) {
            return claim.replay();
        }

        PaymentResult result;
        try {
            result = gateway.charge(idempotencyKey, id, claim.amount(), cardToken);
        } catch (PaymentGatewayException e) {
            tx.executeWithoutResult(s -> lock(id).abortProcessing(true));
            throw e;
        }

        return tx.execute(s -> {
            Order o = lock(id);
            if (o.getStatus() != OrderStatus.PENDING_PAYMENT || !idempotencyKey.equals(o.getPayIdempotencyKey())) {
                log.error("order {} changed while payment {} was in flight", id, result.paymentId());
                throw ApiException.conflict("order " + id + " changed during payment");
            }
            if (result.approved()) {
                for (OrderItem i : sortedItems(o)) products.commit(i.getProductId(), i.getQuantity());
                o.markPaid(result.paymentId(), clock.instant());
            } else {
                releaseReservation(o);
                o.changeStatus(OrderStatus.PAYMENT_FAILED);
            }
            return OrderResponse.from(o);
        });
    }

    // ---------------------------------------------------------------- cancel

    public OrderResponse cancel(long id) {
        record Claim(OrderResponse done, String paymentId) {
        }
        Claim claim = tx.execute(s -> {
            expireIfDueLocked(id);
            Order o = lock(id);
            Instant now = clock.instant();
            if (o.getStatus() != OrderStatus.PENDING_PAYMENT && o.getStatus() != OrderStatus.PAID) {
                throw ApiException.conflict("order " + id + " is " + o.getStatus() + " and cannot be cancelled");
            }
            if (o.isProcessing(now, props.paymentLockTimeout())) {
                throw ApiException.conflict("order " + id + " is being processed");
            }
            if (o.getStatus() == OrderStatus.PENDING_PAYMENT) {
                releaseReservation(o);
                o.changeStatus(OrderStatus.CANCELLED);
                return new Claim(OrderResponse.from(o), null);
            }
            o.beginProcessing(null, now);
            return new Claim(null, o.getPaymentId());
        });
        if (claim.done() != null) {
            return claim.done();
        }

        try {
            gateway.refund(claim.paymentId());
        } catch (PaymentGatewayException e) {
            tx.executeWithoutResult(s -> lock(id).abortProcessing(false));
            throw e;
        }

        return tx.execute(s -> {
            Order o = lock(id);
            if (o.getStatus() != OrderStatus.PAID) {
                throw ApiException.conflict("order " + id + " changed during refund");
            }
            for (OrderItem i : sortedItems(o)) products.restock(i.getProductId(), i.getQuantity());
            if (o.getCouponCode() != null) coupons.release(o.getCouponCode());
            o.changeStatus(OrderStatus.REFUNDED);
            return OrderResponse.from(o);
        });
    }

    // ------------------------------------------------------- ship / deliver

    public OrderResponse ship(long id) {
        return tx.execute(s -> {
            Order o = lock(id);
            if (o.getStatus() != OrderStatus.PAID || o.isProcessing(clock.instant(), props.paymentLockTimeout())) {
                throw ApiException.conflict("order " + id + " is " + o.getStatus() + " and cannot be shipped");
            }
            o.changeStatus(OrderStatus.SHIPPED);
            return OrderResponse.from(o);
        });
    }

    public OrderResponse deliver(long id) {
        return tx.execute(s -> {
            Order o = lock(id);
            if (o.getStatus() != OrderStatus.SHIPPED) {
                throw ApiException.conflict("order " + id + " is " + o.getStatus() + " and cannot be delivered");
            }
            o.changeStatus(OrderStatus.DELIVERED);
            return OrderResponse.from(o);
        });
    }

    // ---------------------------------------------------------------- expiry

    /** Expires every overdue unpaid order; called by the scheduler and before listings. */
    public int expireDue() {
        List<Long> due = orders.findDueIds(OrderStatus.PENDING_PAYMENT, clock.instant(), PageRequest.of(0, 200));
        int expired = 0;
        for (Long id : due) {
            try {
                if (Boolean.TRUE.equals(tx.execute(s -> expireIfDueLocked(id)))) expired++;
            } catch (RuntimeException e) {
                log.warn("failed to expire order {}", id, e);
            }
        }
        return expired;
    }

    /** Must run inside a transaction. */
    private boolean expireIfDueLocked(long id) {
        Order o = orders.findForUpdate(id).orElse(null);
        Instant now = clock.instant();
        if (o == null || o.getStatus() != OrderStatus.PENDING_PAYMENT || !o.isExpiredAt(now)
                || o.isProcessing(now, props.paymentLockTimeout())) {
            return false;
        }
        releaseReservation(o);
        o.changeStatus(OrderStatus.EXPIRED);
        return true;
    }

    // --------------------------------------------------------------- helpers

    private Order lock(long id) {
        return orders.findForUpdate(id).orElseThrow(() -> ApiException.notFound("order", id));
    }

    private void releaseReservation(Order o) {
        for (OrderItem i : sortedItems(o)) products.release(i.getProductId(), i.getQuantity());
        if (o.getCouponCode() != null) coupons.release(o.getCouponCode());
    }

    private static List<OrderItem> sortedItems(Order o) {
        List<OrderItem> items = new ArrayList<>(o.getItems());
        items.sort(Comparator.comparingLong(OrderItem::getProductId));
        return items;
    }
}
