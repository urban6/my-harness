package com.example.order.orders;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ConstraintNames;
import com.example.order.common.error.ErrorCode;
import com.example.order.common.time.Times;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.coupon.DiscountCalculator;
import com.example.order.idempotency.IdempotencyStore;
import com.example.order.idempotency.StoredResponse;
import com.example.order.orders.dto.CreateOrderRequest;
import com.example.order.orders.dto.OrderItemRequest;
import com.example.order.orders.dto.OrderPageResponse;
import com.example.order.orders.dto.OrderResponse;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;
    private final OrderInventory inventory;
    private final IdempotencyStore idempotencyStore;
    private final PaymentGatewayClient pgClient;
    private final OrderProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
                        CouponRepository couponRepository, OrderInventory inventory,
                        IdempotencyStore idempotencyStore, PaymentGatewayClient pgClient,
                        OrderProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
        this.inventory = inventory;
        this.idempotencyStore = idempotencyStore;
        this.pgClient = pgClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 생성 (§6)

    @Transactional
    public StoredResponse create(String userId, CreateOrderRequest req, long idemRecordId) {
        Instant now = Times.now(clock);
        List<OrderItemRequest> items = req.items();

        // ① 404 상품 (요청 순서대로 첫 번째 누락)
        Set<Long> ids = new HashSet<>();
        items.forEach(i -> ids.add(i.productId()));
        Map<Long, Product> products = productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (OrderItemRequest item : items) {
            if (!products.containsKey(item.productId())) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                        "상품을 찾을 수 없습니다: id=" + item.productId());
            }
        }

        // ② 404 쿠폰
        Coupon coupon = null;
        if (req.couponCode() != null) {
            if (req.couponCode().indexOf('\u0000') >= 0) { // PostgreSQL이 NUL 문자열을 거부하므로 조회 없이 미존재 처리
                throw new BusinessException(ErrorCode.COUPON_NOT_FOUND, "쿠폰을 찾을 수 없습니다.");
            }
            coupon = couponRepository.findByCode(req.couponCode())
                    .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND,
                            "쿠폰을 찾을 수 없습니다: code=" + req.couponCode()));
        }

        // ③ 금액 계산
        long subtotal = 0;
        for (OrderItemRequest item : items) {
            subtotal = Math.addExact(subtotal,
                    Math.multiplyExact(products.get(item.productId()).getPrice(), (long) item.quantity()));
        }
        long discount = coupon == null ? 0L : DiscountCalculator.discount(coupon, subtotal);

        // ④ 409 재고: productId 오름차순으로 조건부 UPDATE
        List<OrderItemRequest> byProductId = items.stream()
                .sorted(Comparator.comparing(OrderItemRequest::productId)).toList();
        for (OrderItemRequest item : byProductId) {
            if (productRepository.tryReserve(item.productId(), item.quantity()) == 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                        "재고가 부족합니다: productId=" + item.productId());
            }
        }

        // ⑤ 409 쿠폰
        if (coupon != null) {
            if (now.isBefore(coupon.getValidFrom()) || !now.isBefore(coupon.getValidUntil())) {
                throw new BusinessException(ErrorCode.COUPON_NOT_APPLICABLE, "쿠폰 유효기간이 아닙니다.");
            }
            if (subtotal < coupon.getMinOrderAmount()) {
                throw new BusinessException(ErrorCode.COUPON_NOT_APPLICABLE, "최소 주문 금액 미달입니다.");
            }
            couponRepository.lockById(coupon.getId());
            if (orderRepository.existsActiveCouponUsage(userId, coupon.getCode())) {
                throw new BusinessException(ErrorCode.COUPON_NOT_APPLICABLE, "이미 이 쿠폰을 사용 중인 주문이 있습니다.");
            }
            if (couponRepository.tryUse(coupon.getId()) == 0) {
                throw new BusinessException(ErrorCode.COUPON_EXHAUSTED, "쿠폰이 모두 소진되었습니다.");
            }
        }

        // ⑥ 저장
        Instant expiresAt = Times.truncate(now.plus(properties.paymentTtl()));
        Order order = new Order(userId, coupon == null ? null : coupon.getCode(), subtotal, discount, now, expiresAt);
        int lineNo = 0;
        for (OrderItemRequest item : items) {
            order.addItem(lineNo++, item.productId(), item.quantity(), products.get(item.productId()).getPrice());
        }
        try {
            orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintNames.UX_ORDERS_ACTIVE_USER_COUPON.equals(ConstraintNames.of(e))) {
                throw new BusinessException(ErrorCode.COUPON_NOT_APPLICABLE, "이미 이 쿠폰을 사용 중인 주문이 있습니다.");
            }
            throw e;
        }

        // ⑦ 응답 직렬화 + 멱등 완료 기록 (같은 트랜잭션)
        String location = "/api/orders/" + order.getId();
        String json = toJson(OrderResponse.from(order));
        idempotencyStore.complete(idemRecordId, 201, json, location);
        return new StoredResponse(201, json, location);
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public OrderResponse get(long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public OrderPageResponse list(String userId, OrderStatus status, int size, String cursor) {
        if (userId != null && userId.indexOf('\u0000') >= 0) { // NUL이 든 userId는 존재할 수 없으므로 쿼리 없이 빈 페이지
            return new OrderPageResponse(List.of(), null);
        }
        CursorCodec.Cursor c = cursor == null ? null : CursorCodec.decode(cursor);
        List<Order> rows = orderRepository.findPage(userId, status, c, size + 1);
        boolean hasNext = rows.size() > size;
        List<Order> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext ? CursorCodec.encode(page.get(page.size() - 1)) : null;
        return new OrderPageResponse(page.stream().map(OrderResponse::from).toList(), nextCursor);
    }

    // ------------------------------------------------------------------ 취소·환불 (§8)

    @Transactional
    public OrderResponse cancel(long id) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        Instant now = Times.now(clock);
        switch (order.getStatus()) {
            case PENDING_PAYMENT -> {
                if (!now.isBefore(order.getExpiresAt())) {
                    throw invalidState(order); // 만료 전이는 스위퍼가 담당
                }
                order.cancel();
                inventory.releaseReservation(order);
                inventory.releaseCoupon(order);
            }
            case PAID -> {
                if (order.getPaymentId() != null) {
                    pgClient.refund(order.getPaymentId()); // 장애 시 예외 -> 롤백 -> 503
                }
                order.refund();
                inventory.restock(order);
                inventory.releaseCoupon(order);
            }
            default -> throw invalidState(order);
        }
        orderRepository.flush();
        return OrderResponse.from(order);
    }

    // ------------------------------------------------------------------ 배송 (§8)

    @Transactional
    public OrderResponse ship(long id) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (order.getStatus() != OrderStatus.PAID) {
            throw invalidState(order);
        }
        order.ship();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse deliver(long id) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw invalidState(order);
        }
        order.deliver();
        return OrderResponse.from(order);
    }

    // ------------------------------------------------------------------ 만료 (§9)

    /** 스위퍼 전용. 주문 1건당 1 트랜잭션. 이미 처리됐거나 다른 트랜잭션이 잠근 주문은 건너뛴다. */
    @Transactional
    public boolean expire(long id, Instant now) {
        return orderRepository.lockExpiredForSweep(id, now).map(order -> {
            order.expire();
            inventory.releaseReservation(order);
            inventory.releaseCoupon(order);
            log.debug("expired order id={}", id);
            return true;
        }).orElse(false);
    }

    // ------------------------------------------------------------------ 공통

    private String toJson(OrderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 직렬화 실패", e);
        }
    }

    private static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: id=" + id);
    }

    private static BusinessException invalidState(Order order) {
        return new BusinessException(ErrorCode.INVALID_STATE,
                "처리할 수 없는 주문 상태입니다: id=" + order.getId() + ", status=" + order.getStatus());
    }
}
