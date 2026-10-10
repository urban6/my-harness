package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.config.OrderProperties;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponService;
import com.example.order.idempotency.IdempotencyRecord;
import com.example.order.idempotency.IdempotencyRepository;
import com.example.order.inventory.Inventory;
import com.example.order.order.OrderDtos.CreateOrderRequest;
import com.example.order.order.OrderDtos.ItemRequest;
import com.example.order.order.OrderDtos.OrderPage;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.payment.PaymentGateway;
import com.example.order.payment.PaymentGateway.ChargeResult;
import com.example.order.payment.PaymentGateway.ChargeStatus;
import com.example.order.payment.PaymentGatewayException;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 생명주기. 상태를 바꾸는 모든 작업은 주문 행 잠금(SELECT ... FOR UPDATE) 아래에서 수행하고,
 * 잠금 순서는 항상 주문 → 상품(id 오름차순) → 쿠폰으로 통일해 데드락을 피한다.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orders;
    private final ProductRepository products;
    private final CouponService coupons;
    private final Inventory inventory;
    private final IdempotencyRepository idempotency;
    private final PaymentGateway gateway;
    private final OrderProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public OrderService(OrderRepository orders, ProductRepository products, CouponService coupons,
            Inventory inventory, IdempotencyRepository idempotency, PaymentGateway gateway,
            OrderProperties props, Clock clock, PlatformTransactionManager tm) {
        this.orders = orders;
        this.products = products;
        this.coupons = coupons;
        this.inventory = inventory;
        this.idempotency = idempotency;
        this.gateway = gateway;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
    }

    /** 트랜잭션 결과. 오류로 응답하더라도 DB 변경(예: 만료 처리)은 커밋해야 할 때 사용한다. */
    private record Outcome(OrderResponse order, ApiException error) {

        static Outcome ok(Order o) {
            return new Outcome(OrderResponse.from(o), null);
        }

        static Outcome fail(ApiException e) {
            return new Outcome(null, e);
        }

        OrderResponse unwrap() {
            if (error != null) {
                throw error;
            }
            return order;
        }
    }

    // ---------------------------------------------------------------- 주문 생성

    public OrderResponse create(String userId, String idempotencyKey, CreateOrderRequest req) {
        requireUserId(userId);
        requireIdempotencyKey(idempotencyKey);
        try {
            Map<Long, Integer> lines = mergeLines(req.items());
            String coupon = req.couponCode() == null || req.couponCode().isBlank() ? null : req.couponCode();
            String hash = sha256(lines + "|" + coupon);
            String scope = "order:" + userId;
            try {
                return tx.execute(s -> createInTx(userId, scope, idempotencyKey, hash, lines, coupon));
            } catch (DataIntegrityViolationException e) {
                // 같은 키의 동시 요청: 먼저 커밋된 쪽의 결과를 재생한다.
                OrderResponse replayed = tx.execute(s -> idempotency.findByScopeAndIdemKey(scope, idempotencyKey)
                        .map(rec -> replay(rec, hash)).orElse(null));
                if (replayed == null) {
                    throw e;
                }
                return replayed;
            }
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("AMOUNT_OVERFLOW", "Quantity or amount is too large");
        }
    }

    private OrderResponse createInTx(String userId, String scope, String key, String hash,
            Map<Long, Integer> lines, String couponCode) {
        Optional<IdempotencyRecord> existing = idempotency.findByScopeAndIdemKey(scope, key);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        Instant now = clock.instant();
        // 키를 먼저 선점: 동시 동일 키 요청은 여기서 유니크 제약으로 직렬화된다.
        IdempotencyRecord record = idempotency.saveAndFlush(new IdempotencyRecord(scope, key, hash, null, now));

        List<Long> productIds = lines.keySet().stream().sorted().toList();
        Map<Long, Product> found = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (Long id : productIds) {
            if (!found.containsKey(id)) {
                throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found: " + id);
            }
        }
        for (Long id : productIds) {
            if (!inventory.reserve(id, lines.get(id))) {
                throw ApiException.conflict("INSUFFICIENT_STOCK", "Insufficient stock for product " + id);
            }
        }

        List<OrderItem> items = new ArrayList<>();
        long subtotal = 0;
        for (Map.Entry<Long, Integer> line : lines.entrySet()) {
            long unitPrice = found.get(line.getKey()).getPrice();
            items.add(new OrderItem(line.getKey(), line.getValue(), unitPrice));
            subtotal = Math.addExact(subtotal, Math.multiplyExact(unitPrice, line.getValue().longValue()));
        }

        long discount = 0;
        if (couponCode != null) {
            Coupon coupon = coupons.find(couponCode);
            if (!coupon.isUsableAt(now)) {
                throw ApiException.unprocessable("COUPON_NOT_USABLE", "Coupon is not within its valid period");
            }
            if (subtotal < coupon.getMinOrderAmount()) {
                throw ApiException.unprocessable("COUPON_MIN_ORDER_NOT_MET",
                        "Order amount is below the coupon minimum of " + coupon.getMinOrderAmount());
            }
            if (!coupons.tryUse(couponCode)) {
                throw ApiException.conflict("COUPON_EXHAUSTED", "Coupon has no remaining quantity");
            }
            discount = coupon.discountFor(subtotal);
        }

        Order order = orders.saveAndFlush(
                new Order(userId, items, couponCode, subtotal, discount, now, now.plus(props.paymentTtl())));
        record.setOrderId(order.getId());
        return OrderResponse.from(order);
    }

    private OrderResponse replay(IdempotencyRecord rec, String hash) {
        if (!rec.getRequestHash().equals(hash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request");
        }
        return OrderResponse.from(orders.findById(rec.getOrderId()).orElseThrow());
    }

    private static Map<Long, Integer> mergeLines(List<ItemRequest> items) {
        Map<Long, Integer> lines = new LinkedHashMap<>();
        for (ItemRequest item : items) {
            lines.merge(item.productId(), item.quantity(), Math::addExact);
        }
        return lines;
    }

    // ---------------------------------------------------------------- 조회

    public OrderResponse get(long id) {
        expireIfDue(id);
        return readTx.execute(s -> orders.findById(id).map(OrderResponse::from)
                .orElseThrow(() -> orderNotFound(id)));
    }

    public OrderPage list(String userId, OrderStatus status, Integer size, String cursor) {
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageSize < 1) {
            throw ApiException.badRequest("INVALID_SIZE", "size must be at least 1");
        }
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        Long cursorId = parseCursor(cursor);
        sweepDue();

        Specification<Order> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null && !userId.isBlank()) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (cursorId != null) {
                predicates.add(cb.lessThan(root.get("id"), cursorId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        int limit = pageSize;
        return readTx.execute(s -> {
            List<Order> rows = orders.findBy(spec,
                    q -> q.sortBy(Sort.by(Sort.Direction.DESC, "id")).limit(limit + 1).all());
            boolean hasNext = rows.size() > limit;
            List<Order> page = hasNext ? rows.subList(0, limit) : rows;
            String next = hasNext ? String.valueOf(page.get(page.size() - 1).getId()) : null;
            return new OrderPage(page.stream().map(OrderResponse::from).toList(), next);
        });
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "Invalid cursor");
        }
    }

    // ---------------------------------------------------------------- 결제

    public OrderResponse pay(long orderId, String idempotencyKey, String cardToken) {
        requireIdempotencyKey(idempotencyKey);
        return tx.execute(s -> payInTx(orderId, idempotencyKey, cardToken)).unwrap();
    }

    /**
     * 주문 행 잠금을 유지한 채 PG를 호출한다. 그래서 같은 주문에 대한 동시 결제는 PG에 한 번만 도달하고,
     * PG 통신이 실패하면 트랜잭션이 롤백되어 주문은 PENDING_PAYMENT로 남아 같은 키로 안전하게 재시도할 수 있다.
     */
    private Outcome payInTx(long orderId, String key, String cardToken) {
        Order order = lockOrder(orderId);
        String scope = "pay:" + orderId;
        String hash = sha256(cardToken);

        Optional<IdempotencyRecord> existing = idempotency.findByScopeAndIdemKey(scope, key);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request");
            }
            return Outcome.ok(order);
        }

        Instant now = clock.instant();
        if (order.isPaymentOverdue(now)) {
            expire(order);
            return Outcome.fail(stateConflict(order));
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw stateConflict(order);
        }

        ChargeResult result;
        try {
            result = gateway.charge(order.getId(), order.getTotalPrice(), cardToken, key);
        } catch (PaymentGatewayException e) {
            log.warn("Payment gateway charge failed for order {}: {}", orderId, e.getMessage());
            throw ApiException.badGateway("PAYMENT_GATEWAY_ERROR", "Payment gateway is unavailable; retry later");
        }

        if (result.status() == ChargeStatus.APPROVED) {
            order.markPaid(result.paymentId(), now);
            for (OrderItem item : sortedItems(order)) {
                inventory.commit(item.productId(), item.quantity());
            }
        } else {
            order.changeStatus(OrderStatus.PAYMENT_FAILED);
            release(order);
        }
        idempotency.save(new IdempotencyRecord(scope, key, hash, orderId, now));
        return Outcome.ok(order);
    }

    // ---------------------------------------------------------------- 취소 / 배송

    public OrderResponse cancel(long orderId) {
        return tx.execute(s -> {
            Order order = lockOrder(orderId);
            switch (order.getStatus()) {
                case PENDING_PAYMENT -> {
                    if (order.isPaymentOverdue(clock.instant())) {
                        expire(order);
                        return Outcome.fail(stateConflict(order));
                    }
                    order.changeStatus(OrderStatus.CANCELLED);
                    release(order);
                }
                case PAID -> {
                    try {
                        gateway.refund(order.getPaymentId());
                    } catch (PaymentGatewayException e) {
                        log.warn("Refund failed for order {}: {}", orderId, e.getMessage());
                        throw ApiException.badGateway("PAYMENT_GATEWAY_ERROR",
                                "Payment gateway is unavailable; retry later");
                    }
                    order.changeStatus(OrderStatus.REFUNDED);
                    for (OrderItem item : sortedItems(order)) {
                        inventory.restock(item.productId(), item.quantity());
                    }
                    if (order.getCouponCode() != null) {
                        coupons.release(order.getCouponCode());
                    }
                }
                default -> throw stateConflict(order);
            }
            return Outcome.ok(order);
        }).unwrap();
    }

    public OrderResponse ship(long orderId) {
        return transition(orderId, OrderStatus.PAID, OrderStatus.SHIPPED);
    }

    public OrderResponse deliver(long orderId) {
        return transition(orderId, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    private OrderResponse transition(long orderId, OrderStatus from, OrderStatus to) {
        return tx.execute(s -> {
            Order order = lockOrder(orderId);
            if (order.getStatus() != from) {
                throw stateConflict(order);
            }
            order.changeStatus(to);
            return OrderResponse.from(order);
        });
    }

    // ---------------------------------------------------------------- 만료

    /** 결제 기한이 지난 PENDING_PAYMENT 주문을 모두 만료 처리한다. 결제 진행 중(잠긴) 주문은 건너뛴다. */
    public void sweepDue() {
        List<Long> ids = readTx.execute(s -> orders.findDueIds(clock.instant()));
        if (ids != null) {
            ids.forEach(this::expireIfDue);
        }
    }

    private void expireIfDue(long id) {
        tx.executeWithoutResult(s -> orders.lockIfDue(id, clock.instant()).ifPresent(this::expire));
    }

    private void expire(Order order) {
        order.changeStatus(OrderStatus.EXPIRED);
        release(order);
    }

    // ---------------------------------------------------------------- 공통

    /** 재고 예약과 쿠폰 사용을 되돌린다 (결제 전 종료: 취소·만료·결제 실패). */
    private void release(Order order) {
        for (OrderItem item : sortedItems(order)) {
            inventory.release(item.productId(), item.quantity());
        }
        if (order.getCouponCode() != null) {
            coupons.release(order.getCouponCode());
        }
    }

    private static List<OrderItem> sortedItems(Order order) {
        return order.getItems().stream().sorted(Comparator.comparingLong(OrderItem::productId)).toList();
    }

    private Order lockOrder(long id) {
        return orders.findByIdForUpdate(id).orElseThrow(() -> orderNotFound(id));
    }

    private static ApiException orderNotFound(long id) {
        return ApiException.notFound("ORDER_NOT_FOUND", "Order not found: " + id);
    }

    private static ApiException stateConflict(Order order) {
        String code = order.getStatus() == OrderStatus.EXPIRED ? "ORDER_EXPIRED" : "INVALID_ORDER_STATE";
        return ApiException.conflict(code, "Operation is not allowed while the order is " + order.getStatus());
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > 100) {
            throw ApiException.badRequest("INVALID_USER_ID", "X-User-Id header is required (max 100 characters)");
        }
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required (max 255 characters)");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
