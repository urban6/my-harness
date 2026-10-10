package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ApiResult;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.coupon.DiscountCalculator;
import com.example.order.idempotency.IdempotencyStore;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POST /api/orders 의 비즈니스 트랜잭션 (01 §3.1 [3]~[8]).
 * 락 순서: 상품(id 오름차순) → 쿠폰. 어떤 예외든 전체 롤백 = 예약·쿠폰 사용 전무.
 */
@Service
public class OrderCreationService {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;
    private final OrderRepository orderRepository;
    private final IdempotencyStore idempotencyStore;
    private final OrderPaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrderCreationService(ProductRepository productRepository, CouponRepository couponRepository,
                                OrderRepository orderRepository, IdempotencyStore idempotencyStore,
                                OrderPaymentProperties paymentProperties, ObjectMapper objectMapper, Clock clock) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
        this.orderRepository = orderRepository;
        this.idempotencyStore = idempotencyStore;
        this.paymentProperties = paymentProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ApiResult create(String userId, OrderCreateRequest req, long idempotencyRecordId) {
        List<OrderCreateRequest.Item> reqItems = req.items();

        // [3] 상품 행 잠금 (id 오름차순)
        List<Long> sortedIds = reqItems.stream().map(OrderCreateRequest.Item::productId).sorted().toList();
        Map<Long, Product> products = productRepository.lockAllByIdIn(sortedIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        for (OrderCreateRequest.Item item : reqItems) {
            if (!products.containsKey(item.productId())) {
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + item.productId() + " not found.");
            }
        }

        // [4] 쿠폰 행 잠금
        Coupon coupon = null;
        if (req.couponCode() != null) {
            coupon = couponRepository.lockByCode(req.couponCode()).orElseThrow(() ->
                    new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon " + req.couponCode() + " not found."));
        }
        Instant now = Times.now(clock);

        // [5] 재고 (요청 순서대로 첫 부족 항목)
        for (OrderCreateRequest.Item item : reqItems) {
            Product p = products.get(item.productId());
            if (p.available() < item.quantity()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Product " + p.getId()
                        + " has only " + p.available() + " available, requested " + item.quantity() + ".");
            }
        }

        // [6] 금액 · 쿠폰 조건 (기간 → 최소금액 → 동일 사용자 사용 중 → 소진)
        long subtotal = 0;
        for (OrderCreateRequest.Item item : reqItems) {
            subtotal += products.get(item.productId()).getPrice() * item.quantity();
        }
        long discount = 0;
        if (coupon != null) {
            if (!coupon.isValidAt(now)) {
                throw notApplicable("Coupon is outside its valid period.");
            }
            if (subtotal < coupon.getMinOrderAmount()) {
                throw notApplicable("Order subtotal is below the coupon's minimum order amount.");
            }
            if (orderRepository.existsByUserIdAndCouponIdAndStatusIn(userId, coupon.getId(),
                    OrderStatus.COUPON_IN_USE)) {
                throw notApplicable("The user already has an active order using this coupon.");
            }
            if (coupon.isExhausted()) {
                throw new ApiException(ErrorCode.COUPON_EXHAUSTED, "Coupon has no remaining quantity.");
            }
            discount = DiscountCalculator.discount(coupon.getType(), coupon.getValue(),
                    coupon.getMaxDiscountAmount(), subtotal);
        }

        // [7] 적용
        OrderEntity order = new OrderEntity(userId,
                coupon == null ? null : coupon.getId(),
                coupon == null ? null : coupon.getCode(),
                subtotal, discount, now, now.plus(paymentProperties.ttl()));
        int lineNo = 0;
        for (OrderCreateRequest.Item item : reqItems) {
            order.addItem(item.productId(), item.quantity(), products.get(item.productId()).getPrice(), lineNo++);
        }
        try {
            order = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            if (msg.contains("uk_orders_user_coupon_in_use")) {
                throw notApplicable("The user already has an active order using this coupon.");
            }
            throw e;
        }
        for (OrderCreateRequest.Item item : reqItems) {
            products.get(item.productId()).reserve(item.quantity());
        }
        if (coupon != null) {
            coupon.use();
        }

        // [8] 응답 직렬화 + 멱등 완료 기록 (같은 트랜잭션)
        OrderResponse response = OrderResponse.from(order);
        String body;
        try {
            body = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String location = "/api/orders/" + order.getId();
        idempotencyStore.complete(idempotencyRecordId, 201, body, location);
        return new ApiResult(201, body, location);
    }

    private static ApiException notApplicable(String detail) {
        return new ApiException(ErrorCode.COUPON_NOT_APPLICABLE, detail);
    }
}
