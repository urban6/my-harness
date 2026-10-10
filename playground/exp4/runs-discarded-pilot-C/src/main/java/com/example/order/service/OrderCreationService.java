package com.example.order.service;

import com.example.order.config.AppClock;
import com.example.order.config.OrderProperties;
import com.example.order.domain.Coupon;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.repository.CouponRepository;
import com.example.order.repository.OrderRepository;
import com.example.order.repository.ProductRepository;
import com.example.order.web.dto.OrderCreateRequest;
import com.example.order.web.dto.OrderResponse;
import com.example.order.web.error.ApiException;
import com.example.order.web.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * POST /api/orders (01 문서 5절). 오케스트레이터는 @Transactional 이 아니다:
 * 멱등 begin(autocommit) -> 지연 만료(주문별 독립 트랜잭션) -> 비즈니스 트랜잭션 1개 를 순차 실행한다.
 */
@Service
public class OrderCreationService {

    private final IdempotencyService idempotency;
    private final ExpiryService expiry;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final OrderRepository orders;
    private final AppClock clock;
    private final OrderProperties props;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public OrderCreationService(IdempotencyService idempotency, ExpiryService expiry, ProductRepository products,
                                CouponRepository coupons, OrderRepository orders, AppClock clock,
                                OrderProperties props, ObjectMapper json, PlatformTransactionManager tm) {
        this.idempotency = idempotency;
        this.expiry = expiry;
        this.products = products;
        this.coupons = coupons;
        this.orders = orders;
        this.clock = clock;
        this.props = props;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
    }

    public StoredResponse create(String userId, String idemKey, OrderCreateRequest req) {
        // 1단계 (400): 같은 productId 중복 불가
        Set<Long> seen = new HashSet<>();
        for (OrderCreateRequest.Item item : req.items()) {
            if (!seen.add(item.productId())) {
                throw ApiException.validation("items: duplicate productId " + item.productId());
            }
        }
        // 2단계: 멱등 키
        IdempotencyService.BeginResult begin =
                idempotency.begin(IdempotencyService.Endpoint.ORDER_CREATE, idemKey, Fingerprints.orderCreate(userId, req));
        if (begin instanceof IdempotencyService.Replay replay) {
            return replay.response();
        }
        IdempotencyService.Attempt attempt = ((IdempotencyService.Proceed) begin).attempt();
        try {
            // 3단계: 지연 만료 (방금 만료된 예약이 재고에 반영되도록)
            expiry.expireDue();
            // 4~7단계: 하나의 DB 트랜잭션 (R3.4)
            return DbRetry.run(() -> tx.execute(status -> createInTx(userId, attempt, req)));
        } catch (RuntimeException | Error e) {
            idempotency.release(attempt); // R4.4: 2xx 가 아니면 키 해제
            throw e;
        }
    }

    private StoredResponse createInTx(String userId, IdempotencyService.Attempt attempt, OrderCreateRequest req) {
        // quantity by productId, 오름차순 (락 순서)
        TreeMap<Long, Long> qtyByProduct = new TreeMap<>();
        for (OrderCreateRequest.Item item : req.items()) {
            qtyByProduct.put(item.productId(), item.quantity());
        }

        // 4단계 (404): 상품 먼저(가장 작은 id 보고), 이어서 쿠폰
        Map<Long, Long> priceById = new HashMap<>();
        for (ProductRepository.PriceView v : products.findPrices(qtyByProduct.keySet())) {
            priceById.put(v.getId(), v.getPrice());
        }
        for (Long productId : qtyByProduct.keySet()) {
            if (!priceById.containsKey(productId)) {
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + productId + " not found.");
            }
        }
        String couponCode = req.couponCode();
        if (couponCode != null && !coupons.existsByCode(couponCode)) {
            throw new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon not found.");
        }

        // 5단계 (409 재고): 상품 id 오름차순 조건부 UPDATE
        for (Map.Entry<Long, Long> e : qtyByProduct.entrySet()) {
            if (products.reserve(e.getKey(), e.getValue()) == 0) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock for product " + e.getKey() + ".");
            }
        }

        long subtotal = 0;
        for (OrderCreateRequest.Item item : req.items()) {
            subtotal = Math.addExact(subtotal, Math.multiplyExact(priceById.get(item.productId()), item.quantity()));
        }

        // 6단계 (409 쿠폰): 쿠폰 행 FOR UPDATE 후 판정
        Coupon coupon = null;
        long discount = 0;
        Instant now;
        if (couponCode != null) {
            coupon = coupons.findLockedByCode(couponCode)
                    .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon not found."));
            now = clock.now(); // 락 획득 직후의 단일 시각: 기간 판정과 createdAt 양쪽에 사용
            if (now.isBefore(coupon.getValidFrom()) || !now.isBefore(coupon.getValidUntil())) {
                throw notApplicable("Coupon is not within its validity period.");
            }
            if (subtotal < coupon.getMinOrderAmount()) {
                throw notApplicable("Order subtotal is below the coupon's minimum order amount.");
            }
            if (orders.existsByCouponIdAndUserIdAndStatusIn(coupon.getId(), userId, OrderStatus.COUPON_ACTIVE)) {
                throw notApplicable("The user already has an active order using this coupon.");
            }
            if (coupon.getUsedCount() >= coupon.getTotalQuantity()
                    || coupons.increaseUsed(coupon.getId()) == 0) {
                throw new ApiException(ErrorCode.COUPON_EXHAUSTED, "Coupon is exhausted.");
            }
            discount = DiscountCalculator.discount(subtotal, coupon.getType(), coupon.getValue(),
                    coupon.getMaxDiscountAmount());
        } else {
            now = clock.now();
        }

        // 7단계: 주문 INSERT (요청 순서대로 항목 추가)
        Order order = new Order(userId, coupon == null ? null : coupon.getId(), coupon == null ? null : coupon.getCode(),
                subtotal, discount, now, props.paymentTtl());
        for (OrderCreateRequest.Item item : req.items()) {
            order.addItem(item.productId(), item.quantity(), priceById.get(item.productId()));
        }
        try {
            orders.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintNames.violated(e, "uq_orders_active_coupon_user")) {
                throw notApplicable("The user already has an active order using this coupon.");
            }
            throw e;
        }

        StoredResponse response = StoredResponse.json(201, "/api/orders/" + order.getId(), render(order));
        idempotency.complete(attempt, response);
        return response;
    }

    private String render(Order order) {
        try {
            return json.writeValueAsString(OrderResponse.from(order));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize order response", e);
        }
    }

    private static ApiException notApplicable(String detail) {
        return new ApiException(ErrorCode.COUPON_NOT_APPLICABLE, detail);
    }
}
