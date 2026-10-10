package com.example.order.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.order.common.error.ConflictException;
import com.example.order.common.error.CouponNotApplicableException;
import com.example.order.common.error.InvalidRequestException;
import com.example.order.common.error.NotFoundException;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderPage;
import com.example.order.order.dto.OrderResponse;
import com.example.order.payment.PaymentResult;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문의 DB 상태 전이만 담당한다. 외부 PG 호출은 트랜잭션 밖에서 {@link OrderPaymentService} 가 하고,
 * 이 클래스의 begin / complete / abort 메서드가 호출 전후의 짧은 트랜잭션을 맡는다.
 * 재고·쿠폰은 항상 "상품(productId 오름차순) → 쿠폰" 순서로 건드려 데드락을 피한다.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    /** 결제 시작 결과. replay 가 있으면 이미 처리된 요청이므로 PG 를 호출하지 않는다. */
    public record PaymentStart(OrderResponse replay, long amount) {}

    /** 취소 시작 결과. done 이 있으면 완료, 없으면 paymentId 로 환불을 진행해야 한다. */
    public record CancelStart(OrderResponse done, String paymentId) {}

    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
                        CouponRepository couponRepository, OrderProperties properties, Clock clock) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 생성 / 조회

    @Transactional
    public OrderResponse create(long userId, String idempotencyKey, CreateOrderRequest request) {
        orderRepository.lockIdempotencyKey(("create:" + userId + ":" + idempotencyKey).hashCode());

        String hash = fingerprint(request);
        Optional<Order> existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw new ConflictException("Idempotency-Key 가 다른 요청에 이미 사용되었습니다.");
            }
            return OrderResponse.from(existing.get());
        }

        Map<Long, Integer> quantities = mergeQuantities(request);
        Map<Long, Product> products = productRepository.findAllById(quantities.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<OrderItem> items = new ArrayList<>();
        long subtotal = 0;
        for (Map.Entry<Long, Integer> e : quantities.entrySet()) {
            Product product = products.get(e.getKey());
            if (product == null) {
                throw new NotFoundException("상품을 찾을 수 없습니다: id=" + e.getKey());
            }
            items.add(new OrderItem(product.getId(), e.getValue(), product.getPrice()));
            subtotal = Math.addExact(subtotal, Math.multiplyExact(product.getPrice(), e.getValue().longValue()));
        }

        Instant now = clock.instant();
        Coupon coupon = null;
        long discount = 0;
        if (request.couponCode() != null) {
            coupon = couponRepository.findByCode(request.couponCode())
                    .orElseThrow(() -> new NotFoundException("쿠폰을 찾을 수 없습니다: code=" + request.couponCode()));
            coupon.assertApplicable(subtotal, now);
            discount = coupon.discountFor(subtotal);
        }

        for (OrderItem item : items) {
            if (productRepository.reserve(item.getProductId(), item.getQuantity()) == 0) {
                throw new ConflictException("재고가 부족합니다: productId=" + item.getProductId());
            }
        }
        if (coupon != null && couponRepository.use(coupon.getCode()) == 0) {
            throw new CouponNotApplicableException("소진된 쿠폰입니다: " + coupon.getCode());
        }

        Order order = new Order(userId, idempotencyKey, hash, items, coupon == null ? null : coupon.getCode(),
                subtotal, discount, now, now.plus(properties.paymentTtl()));
        return OrderResponse.from(orderRepository.save(order));
    }

    /** 조회 시점에 만료 시각이 지났으면 먼저 만료 처리해 항상 최신 상태를 보여준다. */
    @Transactional
    public OrderResponse get(long id) {
        expireIfDue(id);
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    public OrderPage list(Long userId, OrderStatus status, int size, String cursor) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("size 는 1~" + MAX_PAGE_SIZE + " 사이여야 합니다.");
        }
        List<Order> rows = orderRepository.search(userId, status, decodeCursor(cursor), PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<Order> page = hasMore ? rows.subList(0, size) : rows;
        String nextCursor = hasMore ? encodeCursor(page.get(page.size() - 1).getId()) : null;
        return new OrderPage(page.stream().map(OrderResponse::from).toList(), nextCursor);
    }

    // ---------------------------------------------------------------- 만료

    @Transactional
    public void expireIfDue(long id) {
        Instant now = clock.instant();
        if (!orderRepository.isDue(id, OrderStatus.PENDING_PAYMENT, now)) {
            return;
        }
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT
                && !order.getExpiresAt().isAfter(now)
                && !order.isGatewayBusy(now, properties.gatewayLockTimeout())) {
            releaseReservation(order);
            order.changeStatus(OrderStatus.EXPIRED);
        }
    }

    public List<Long> findDueOrderIds() {
        return orderRepository.findDueIds(OrderStatus.PENDING_PAYMENT, clock.instant(), PageRequest.of(0, 100));
    }

    // ---------------------------------------------------------------- 결제

    @Transactional
    public PaymentStart beginPayment(long id, String key) {
        Order order = lock(id);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            if (key.equals(order.getPaymentKey())) {
                return new PaymentStart(OrderResponse.from(order), 0);
            }
            throw new ConflictException("결제할 수 없는 주문 상태입니다: " + order.getStatus());
        }
        Instant now = clock.instant();
        if (order.isGatewayBusy(now, properties.gatewayLockTimeout())) {
            throw new ConflictException("결제가 이미 진행 중입니다.");
        }
        order.startPayment(key, now);
        return new PaymentStart(null, order.getTotalPrice());
    }

    @Transactional
    public OrderResponse completePayment(long id, PaymentResult result) {
        Order order = lock(id);
        order.endGatewayCall();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ConflictException("결제 결과를 반영할 수 없는 주문 상태입니다: " + order.getStatus());
        }
        if (result.approved()) {
            for (OrderItem item : order.getItems()) {
                productRepository.commit(item.getProductId(), item.getQuantity());
            }
            order.markPaid(result.paymentId(), clock.instant());
        } else {
            releaseReservation(order);
            order.changeStatus(OrderStatus.PAYMENT_FAILED);
        }
        return OrderResponse.from(order);
    }

    /** PG 호출이 실패했을 때 진행 중 표시를 풀어 재시도할 수 있게 한다. */
    @Transactional
    public void abortGatewayCall(long id) {
        lock(id).endGatewayCall();
    }

    // ---------------------------------------------------------------- 취소 / 환불

    @Transactional
    public CancelStart beginCancel(long id) {
        Order order = lock(id);
        Instant now = clock.instant();
        switch (order.getStatus()) {
            case PENDING_PAYMENT -> {
                assertNotBusy(order, now);
                releaseReservation(order);
                order.changeStatus(OrderStatus.CANCELLED);
                return new CancelStart(OrderResponse.from(order), null);
            }
            case PAID -> {
                assertNotBusy(order, now);
                order.startGatewayCall(now);
                return new CancelStart(null, order.getPaymentId());
            }
            default -> throw new ConflictException("취소할 수 없는 주문 상태입니다: " + order.getStatus());
        }
    }

    @Transactional
    public OrderResponse completeRefund(long id) {
        Order order = lock(id);
        order.endGatewayCall();
        if (order.getStatus() != OrderStatus.PAID) {
            throw new ConflictException("환불을 반영할 수 없는 주문 상태입니다: " + order.getStatus());
        }
        for (OrderItem item : order.getItems()) {
            productRepository.restock(item.getProductId(), item.getQuantity());
        }
        releaseCoupon(order);
        order.changeStatus(OrderStatus.REFUNDED);
        return OrderResponse.from(order);
    }

    // ---------------------------------------------------------------- 배송

    @Transactional
    public OrderResponse ship(long id) {
        return transition(id, OrderStatus.PAID, OrderStatus.SHIPPED);
    }

    @Transactional
    public OrderResponse deliver(long id) {
        return transition(id, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    private OrderResponse transition(long id, OrderStatus from, OrderStatus to) {
        Order order = lock(id);
        if (order.getStatus() != from) {
            throw new ConflictException("%s 상태에서만 %s 로 변경할 수 있습니다. 현재: %s".formatted(from, to, order.getStatus()));
        }
        assertNotBusy(order, clock.instant());
        order.changeStatus(to);
        return OrderResponse.from(order);
    }

    // ---------------------------------------------------------------- 내부

    private Order lock(long id) {
        return orderRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
    }

    private void assertNotBusy(Order order, Instant now) {
        if (order.isGatewayBusy(now, properties.gatewayLockTimeout())) {
            throw new ConflictException("결제 처리가 진행 중인 주문입니다.");
        }
    }

    /** 결제 확정 전 주문이 끝날 때(취소·만료·결제 실패): 재고 예약과 쿠폰 사용을 되돌린다. */
    private void releaseReservation(Order order) {
        for (OrderItem item : order.getItems()) {
            productRepository.release(item.getProductId(), item.getQuantity());
        }
        releaseCoupon(order);
    }

    private void releaseCoupon(Order order) {
        if (order.getCouponCode() != null) {
            couponRepository.release(order.getCouponCode());
        }
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("주문을 찾을 수 없습니다: id=" + id);
    }

    /** 같은 상품이 여러 줄이면 수량을 합치고 productId 오름차순으로 정렬한다. */
    private static Map<Long, Integer> mergeQuantities(CreateOrderRequest request) {
        Map<Long, Integer> merged = new TreeMap<>();
        for (CreateOrderRequest.Item item : request.items()) {
            merged.merge(item.productId(), item.quantity(),
                    (a, b) -> (int) Math.min((long) a + b, Integer.MAX_VALUE));
        }
        return merged;
    }

    private static String fingerprint(CreateOrderRequest request) {
        StringBuilder sb = new StringBuilder();
        mergeQuantities(request).forEach((id, qty) -> sb.append(id).append(':').append(qty).append(';'));
        sb.append('|').append(request.couponCode() == null ? "" : request.couponCode());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encodeCursor(long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(id).getBytes(StandardCharsets.UTF_8));
    }

    private static Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("cursor 형식이 올바르지 않습니다.");
        }
    }
}
