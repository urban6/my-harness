package com.example.order.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.order.common.ApiException;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.payment.PaymentGateway;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;

import jakarta.persistence.criteria.Predicate;

/**
 * 주문 생성·결제·취소·배송. 재고/쿠폰 수량은 조건부 UPDATE로 원자적으로 갱신하고,
 * 상태 전이는 주문 행 비관적 락 아래에서 수행한다.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPIRE_BATCH = 500;

    private final PurchaseOrderRepository orders;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final PaymentGateway gateway;
    private final OrderProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public OrderService(PurchaseOrderRepository orders, ProductRepository products, CouponRepository coupons,
            PaymentGateway gateway, OrderProperties props, Clock clock, PlatformTransactionManager txManager) {
        this.orders = orders;
        this.products = products;
        this.coupons = coupons;
        this.gateway = gateway;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    // ---------------------------------------------------------------- 생성

    public OrderResponse create(String userId, String idempotencyKey, CreateOrderRequest req) {
        if (userId == null || userId.isBlank()) {
            throw ApiException.badRequest("X-User-Id header is required");
        }
        String key = blankToNull(idempotencyKey);
        String couponCode = blankToNull(req.couponCode());
        Map<Long, Long> lines = mergeLines(req);
        String hash = requestHash(lines, couponCode);

        if (key != null) {
            Optional<OrderResponse> replay = replay(userId, key, hash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        try {
            return tx.execute(s -> doCreate(userId, key, hash, lines, couponCode));
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 동시 요청이 먼저 커밋됨: 이 트랜잭션은 롤백되어 예약이 남지 않는다.
            if (key != null) {
                Optional<OrderResponse> replay = replay(userId, key, hash);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            throw e;
        }
    }

    private Optional<OrderResponse> replay(String userId, String key, String hash) {
        Optional<PurchaseOrder> existing = readTx.execute(s -> orders.findByUserIdAndIdempotencyKey(userId, key));
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (!hash.equals(existing.get().getRequestHash())) {
            throw ApiException.unprocessable("Idempotency Key Reuse",
                    "Idempotency-Key was already used with a different request");
        }
        return Optional.of(get(existing.get().getId()));
    }

    private OrderResponse doCreate(String userId, String key, String hash, Map<Long, Long> lines,
            String couponCode) {
        Instant now = now();

        Map<Long, Product> found = products.findAllByIdIn(lines.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (Long productId : lines.keySet()) {
            if (!found.containsKey(productId)) {
                throw ApiException.notFound("product " + productId + " not found");
            }
        }

        long subtotal = 0;
        for (Map.Entry<Long, Long> line : lines.entrySet()) {
            subtotal = Math.addExact(subtotal,
                    Math.multiplyExact(found.get(line.getKey()).getPrice(), line.getValue()));
        }

        // 상품 id 오름차순으로 예약해 동시 주문 간 교착을 피한다.
        for (Map.Entry<Long, Long> line : lines.entrySet()) {
            if (products.reserve(line.getKey(), line.getValue()) == 0) {
                throw ApiException.conflict("Insufficient Stock",
                        "not enough stock for product " + line.getKey());
            }
        }

        long discount = 0;
        if (couponCode != null) {
            Coupon coupon = coupons.findByCode(couponCode)
                    .orElseThrow(() -> ApiException.notFound("coupon " + couponCode + " not found"));
            if (!coupon.isActiveAt(now)) {
                throw ApiException.unprocessable("Coupon Not Applicable", "coupon is not within its valid period");
            }
            if (subtotal < coupon.getMinOrderAmount()) {
                throw ApiException.unprocessable("Coupon Not Applicable",
                        "order amount is below the coupon minimum of " + coupon.getMinOrderAmount());
            }
            if (coupons.consume(coupon.getId()) == 0) {
                throw ApiException.conflict("Coupon Exhausted", "coupon " + couponCode + " has no remaining quantity");
            }
            discount = coupon.discountFor(subtotal);
        }

        PurchaseOrder order = new PurchaseOrder(userId, couponCode, subtotal, discount, now,
                now.plus(props.paymentTtl()), key, hash);
        for (Map.Entry<Long, Long> line : lines.entrySet()) {
            order.addItem(line.getKey(), line.getValue(), found.get(line.getKey()).getPrice());
        }
        return OrderResponse.from(orders.saveAndFlush(order));
    }

    private Map<Long, Long> mergeLines(CreateOrderRequest req) {
        Map<Long, Long> merged = new TreeMap<>();
        for (CreateOrderRequest.Item item : req.items()) {
            merged.merge(item.productId(), item.quantity(), Math::addExact);
        }
        return merged;
    }

    private String requestHash(Map<Long, Long> lines, String couponCode) {
        String canonical = lines.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(",")) + "|" + (couponCode == null ? "" : couponCode);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 조회

    public OrderResponse get(long id) {
        OrderResponse view = readTx.execute(s -> OrderResponse.from(find(id)));
        if (isDue(view)) {
            expireIfDue(id);
            view = readTx.execute(s -> OrderResponse.from(find(id)));
        }
        return view;
    }

    public OrderPage list(String userId, OrderStatus status, Integer size, String cursor) {
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Long before = parseCursor(cursor);

        expireDueOrders();

        Specification<PurchaseOrder> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (userId != null && !userId.isBlank()) {
                ps.add(cb.equal(root.get("userId"), userId));
            }
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (before != null) {
                ps.add(cb.lessThan(root.get("id"), before));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };

        return readTx.execute(s -> {
            List<PurchaseOrder> rows = orders.findBy(spec,
                    q -> q.sortBy(Sort.by(Sort.Direction.DESC, "id")).limit(pageSize + 1).all());
            boolean hasMore = rows.size() > pageSize;
            List<PurchaseOrder> page = hasMore ? rows.subList(0, pageSize) : rows;
            String next = hasMore ? String.valueOf(page.get(page.size() - 1).getId()) : null;
            return new OrderPage(page.stream().map(OrderResponse::from).toList(), next);
        });
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid cursor");
        }
    }

    // ---------------------------------------------------------------- 결제

    private record Outcome(OrderResponse view, boolean expired) {
    }

    public OrderResponse pay(long id, String idempotencyKey, String cardToken) {
        String payKey = blankToNull(idempotencyKey) != null ? idempotencyKey.trim() : "pay-" + id;

        Outcome outcome = tx.execute(s -> {
            PurchaseOrder order = lock(id);
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                if (payKey.equals(order.getPaymentKey())) {
                    return new Outcome(OrderResponse.from(order), false); // 같은 키의 재시도
                }
                throw notPayable(order);
            }
            Instant now = now();
            if (order.isExpiredAt(now)) {
                expire(order);
                return new Outcome(null, true);
            }

            // 락을 쥔 채 호출하므로 같은 주문에 대한 동시 결제가 PG에 두 번 나가지 않는다.
            // 통신 오류는 ApiException(502)으로 롤백되어 주문은 PENDING_PAYMENT로 남고 재시도할 수 있다.
            PaymentGateway.PaymentResult result = gateway.charge(order.getId(), order.getTotalPrice(), cardToken,
                    payKey);
            if (result.approved()) {
                for (OrderItem item : order.getItems()) {
                    products.commit(item.getProductId(), item.getQuantity());
                }
                order.markPaid(result.paymentId(), payKey, now);
            } else {
                releaseReservation(order);
                order.markPaymentFailed(result.paymentId(), payKey);
            }
            return new Outcome(OrderResponse.from(order), false);
        });

        if (outcome.expired()) {
            throw ApiException.conflict("Order Expired", "order " + id + " has expired");
        }
        return outcome.view();
    }

    // ---------------------------------------------------------------- 취소 / 환불

    public OrderResponse cancel(long id) {
        Outcome outcome = tx.execute(s -> {
            PurchaseOrder order = lock(id);
            switch (order.getStatus()) {
                case PENDING_PAYMENT -> {
                    if (order.isExpiredAt(now())) {
                        expire(order);
                        return new Outcome(null, true);
                    }
                    releaseReservation(order);
                    order.setStatus(OrderStatus.CANCELLED);
                }
                case PAID -> {
                    gateway.refund(order.getPaymentId());
                    for (OrderItem item : order.getItems()) {
                        products.restock(item.getProductId(), item.getQuantity());
                    }
                    releaseCoupon(order);
                    order.setStatus(OrderStatus.REFUNDED);
                }
                default -> throw ApiException.conflict("Invalid State",
                        "order " + id + " cannot be cancelled in status " + order.getStatus());
            }
            return new Outcome(OrderResponse.from(order), false);
        });

        if (outcome.expired()) {
            throw ApiException.conflict("Order Expired", "order " + id + " has expired");
        }
        return outcome.view();
    }

    // ---------------------------------------------------------------- 배송

    public OrderResponse ship(long id) {
        return transition(id, OrderStatus.PAID, OrderStatus.SHIPPED);
    }

    public OrderResponse deliver(long id) {
        return transition(id, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    private OrderResponse transition(long id, OrderStatus from, OrderStatus to) {
        return tx.execute(s -> {
            PurchaseOrder order = lock(id);
            if (order.getStatus() != from) {
                throw ApiException.conflict("Invalid State",
                        "order " + id + " must be " + from + " to become " + to + " but is " + order.getStatus());
            }
            order.setStatus(to);
            return OrderResponse.from(order);
        });
    }

    // ---------------------------------------------------------------- 만료

    /** 결제 기한이 지난 주문을 만료 처리한다(스케줄러와 목록 조회에서 호출). */
    public void expireDueOrders() {
        List<Long> due = readTx.execute(s -> orders.findDueIds(now(), PageRequest.of(0, EXPIRE_BATCH)));
        for (Long id : due) {
            try {
                expireIfDue(id);
            } catch (RuntimeException e) {
                log.warn("failed to expire order {}", id, e);
            }
        }
    }

    private void expireIfDue(long id) {
        tx.executeWithoutResult(s -> orders.findByIdForUpdate(id).ifPresent(order -> {
            if (order.isExpiredAt(now())) {
                expire(order);
            }
        }));
    }

    private void expire(PurchaseOrder order) {
        releaseReservation(order);
        order.setStatus(OrderStatus.EXPIRED);
    }

    private boolean isDue(OrderResponse view) {
        return view.status() == OrderStatus.PENDING_PAYMENT && !view.expiresAt().isAfter(now());
    }

    // ---------------------------------------------------------------- 공통

    private void releaseReservation(PurchaseOrder order) {
        for (OrderItem item : order.getItems()) {
            products.release(item.getProductId(), item.getQuantity());
        }
        releaseCoupon(order);
    }

    private void releaseCoupon(PurchaseOrder order) {
        if (order.getCouponCode() != null) {
            coupons.release(order.getCouponCode());
        }
    }

    private PurchaseOrder find(long id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("order " + id + " not found"));
    }

    private PurchaseOrder lock(long id) {
        return orders.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("order " + id + " not found"));
    }

    private ApiException notPayable(PurchaseOrder order) {
        return ApiException.conflict("Invalid State",
                "order " + order.getId() + " cannot be paid in status " + order.getStatus());
    }

    /** PostgreSQL timestamptz 정밀도(마이크로초)에 맞춰 저장 전후 값이 달라지지 않게 한다. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
