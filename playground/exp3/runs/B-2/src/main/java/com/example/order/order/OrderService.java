package com.example.order.order;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.example.order.common.error.DomainException;
import com.example.order.coupon.CouponService;
import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderPage;
import com.example.order.order.dto.OrderResponse;
import com.example.order.order.dto.PayRequest;
import com.example.order.payment.PaymentGateway;
import com.example.order.payment.PaymentGateway.ChargeResult;
import com.example.order.product.ProductService;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 락 순서: 주문 행 → 상품 행(productId 오름차순) → 쿠폰 행. 모든 경로가 이 순서를 지켜 데드락을 피한다.
 * 상태 전이 메서드는 DomainException(409 등)이 나도 그 전에 반영한 만료 처리는 커밋하도록 noRollbackFor를 둔다.
 * 외부 PG 호출은 주문 행 잠금을 쥔 채 수행한다 — 같은 주문의 중복 결제·환불을 막기 위함이며 PG 타임아웃으로 점유 시간이 제한된다.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final CouponService couponService;
    private final PaymentGateway paymentGateway;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, ProductService productService, CouponService couponService,
                        PaymentGateway paymentGateway, OrderProperties properties, Clock clock) {
        this.orderRepository = orderRepository;
        this.productService = productService;
        this.couponService = couponService;
        this.paymentGateway = paymentGateway;
        this.properties = properties;
        this.clock = clock;
    }

    /** 주문 생성: 재고 예약 + 쿠폰 사용을 한 트랜잭션으로. 같은 (사용자, Idempotency-Key)는 같은 주문을 돌려준다. */
    @Transactional
    public OrderResponse create(String userId, String idempotencyKey, CreateOrderRequest request) {
        requireText(userId, "X-User-Id");
        requireText(idempotencyKey, "Idempotency-Key");
        String couponCode = request.couponCode() == null || request.couponCode().isBlank() ? null : request.couponCode();

        SortedMap<Long, Integer> quantities = new TreeMap<>();
        for (CreateOrderRequest.Item item : request.items()) {
            if (quantities.putIfAbsent(item.productId(), item.quantity()) != null) {
                throw DomainException.invalid("DUPLICATE_ITEM", "같은 상품이 중복되었습니다: productId=" + item.productId());
            }
        }
        String fingerprint = request.items().stream()
                .map(i -> i.productId() + "x" + i.quantity())
                .collect(Collectors.joining(",")) + "|" + couponCode;

        orderRepository.lockIdempotencyKey(userId + "\n" + idempotencyKey);
        Optional<Order> existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestFingerprint().equals(fingerprint)) {
                throw DomainException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                        "같은 Idempotency-Key로 다른 내용의 주문을 요청할 수 없습니다.");
            }
            return OrderResponse.from(existing.get());
        }

        Instant now = clock.instant();
        Map<Long, Long> prices = productService.reserve(quantities);
        List<OrderItem> items = request.items().stream()
                .map(i -> new OrderItem(i.productId(), i.quantity(), prices.get(i.productId())))
                .toList();
        long subtotal = items.stream().mapToLong(i -> i.quantity() * i.unitPrice()).sum();
        long discount = couponCode == null ? 0 : couponService.apply(couponCode, subtotal, now);

        Order order = new Order(userId, idempotencyKey, fingerprint, items, couponCode, subtotal, discount,
                now, now.plus(properties.paymentTtl()));
        return OrderResponse.from(orderRepository.save(order));
    }

    /** 단건 조회. 결제 기한이 지난 주문이면 먼저 만료 처리한다. */
    @Transactional
    public OrderResponse get(Long id) {
        if (orderRepository.existsByIdAndStatusAndExpiresAtLessThanEqual(id, OrderStatus.PENDING_PAYMENT, clock.instant())) {
            expireIfDue(lock(id));
        }
        return OrderResponse.from(orderRepository.findById(id).orElseThrow(() -> notFound(id)));
    }

    /** 커서 기반 목록(id 내림차순). cursor는 직전 페이지 마지막 주문 id. */
    @Transactional
    public OrderPage list(String userId, OrderStatus status, Integer size, String cursor) {
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw DomainException.invalid("INVALID_PAGE_SIZE", "size는 1~" + MAX_PAGE_SIZE + " 이어야 합니다.");
        }
        Long cursorId = parseCursor(cursor);

        expireDueOrders();

        Specification<Order> spec = (root, query, cb) -> cb.conjunction();
        if (userId != null && !userId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("userId"), userId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (cursorId != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.<Long>get("id"), cursorId));
        }
        List<Order> rows = orderRepository.findBy(spec,
                q -> q.sortBy(Sort.by(Sort.Direction.DESC, "id")).limit(pageSize + 1).all());

        boolean hasNext = rows.size() > pageSize;
        List<Order> page = hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasNext ? String.valueOf(page.get(page.size() - 1).getId()) : null;
        return new OrderPage(page.stream().map(OrderResponse::from).toList(), nextCursor);
    }

    @Transactional(noRollbackFor = DomainException.class)
    public OrderResponse pay(Long id, String payIdempotencyKey, PayRequest request) {
        requireText(payIdempotencyKey, "Idempotency-Key");
        Order order = lock(id);

        // 같은 키로 이미 결제 결과가 기록된 재요청은 현재 주문을 그대로 돌려준다(PG 재호출 없음).
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT
                && Objects.equals(order.getPayIdempotencyKey(), payIdempotencyKey)) {
            return OrderResponse.from(order);
        }
        expireIfDue(order);
        order.requireStatus(OrderStatus.PENDING_PAYMENT);

        Instant now = clock.instant();
        if (order.getTotalPrice() == 0) {      // 전액 할인: PG 호출 없이 결제 완료
            approve(order, null, payIdempotencyKey, now);
            return OrderResponse.from(order);
        }
        ChargeResult result = paymentGateway.charge(order.getId(), order.getTotalPrice(),
                request.cardToken(), payIdempotencyKey);
        if (result.approved()) {
            approve(order, result.paymentId(), payIdempotencyKey, now);
        } else {
            order.markPaymentFailed(payIdempotencyKey);
            releaseReservation(order);
        }
        return OrderResponse.from(order);
    }

    /** 결제 대기 주문은 예약 해제 후 CANCELLED, 결제 완료 주문은 PG 환불 후 REFUNDED. */
    @Transactional(noRollbackFor = DomainException.class)
    public OrderResponse cancel(Long id) {
        Order order = lock(id);
        expireIfDue(order);
        order.requireStatus(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID);

        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            order.cancel();
            releaseReservation(order);
        } else {
            if (order.getPaymentId() != null) {
                paymentGateway.refund(order.getPaymentId());
            }
            order.refund();
            productService.restock(order.quantitiesByProduct());
            if (order.getCouponCode() != null) {
                couponService.release(order.getCouponCode());
            }
        }
        return OrderResponse.from(order);
    }

    @Transactional(noRollbackFor = DomainException.class)
    public OrderResponse ship(Long id) {
        Order order = lock(id);
        order.ship();
        return OrderResponse.from(order);
    }

    @Transactional(noRollbackFor = DomainException.class)
    public OrderResponse deliver(Long id) {
        Order order = lock(id);
        order.deliver();
        return OrderResponse.from(order);
    }

    /** 결제 기한이 지난 주문을 만료시키고 예약을 해제한다(스케줄러·목록 조회에서 호출). 처리한 주문 수를 돌려준다. */
    @Transactional
    public int expireDueOrders() {
        List<OrderRepository.OrderIdOnly> due = orderRepository
                .findTop100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(OrderStatus.PENDING_PAYMENT, clock.instant());
        for (OrderRepository.OrderIdOnly row : due) {
            expireIfDue(lock(row.getId()));
        }
        return due.size();
    }

    private void approve(Order order, String paymentId, String payIdempotencyKey, Instant now) {
        order.markPaid(paymentId, payIdempotencyKey, now);
        productService.confirm(order.quantitiesByProduct());
    }

    private void expireIfDue(Order order) {
        if (order.isExpiredAt(clock.instant())) {
            order.expire();
            releaseReservation(order);
        }
    }

    private void releaseReservation(Order order) {
        productService.release(order.quantitiesByProduct());
        if (order.getCouponCode() != null) {
            couponService.release(order.getCouponCode());
        }
    }

    private Order lock(Long id) {
        return orderRepository.findWithLockById(id).orElseThrow(() -> notFound(id));
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(cursor);
        } catch (NumberFormatException e) {
            throw DomainException.invalid("INVALID_CURSOR", "cursor 형식이 올바르지 않습니다.");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw DomainException.invalid("MISSING_HEADER", name + " 헤더가 필요합니다.");
        }
    }

    private static DomainException notFound(Long id) {
        return DomainException.notFound("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다: id=" + id);
    }
}
