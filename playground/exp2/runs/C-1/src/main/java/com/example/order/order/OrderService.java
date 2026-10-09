package com.example.order.order;

import com.example.order.common.error.ApiException;
import com.example.order.common.error.ErrorCode;
import com.example.order.common.time.TimeProvider;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponPolicy;
import com.example.order.coupon.CouponRepository;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.idempotency.StoredResponse;
import com.example.order.product.Product;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 주문 생성(R3)·조회·목록(R9). */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final OrderResourceSupport resources;
    private final IdempotencyService idempotency;
    private final TimeProvider time;
    private final OrderProperties properties;

    public OrderService(OrderRepository orderRepository, CouponRepository couponRepository,
                        OrderResourceSupport resources, IdempotencyService idempotency,
                        TimeProvider time, OrderProperties properties) {
        this.orderRepository = orderRepository;
        this.couponRepository = couponRepository;
        this.resources = resources;
        this.idempotency = idempotency;
        this.time = time;
        this.properties = properties;
    }

    @Transactional
    public StoredResponse create(String userId, CreateOrderRequest request, long idempotencyRecordId) {
        Instant now = time.now();
        List<CreateOrderRequest.Item> items = request.items();

        // 4. 상품 락(id 오름차순) — 없으면 404
        Map<Long, Product> products = resources.lockProducts(items.stream().map(CreateOrderRequest.Item::productId).toList());

        // 5. 쿠폰 락 — 없으면 404
        Coupon coupon = null;
        if (request.couponCode() != null) {
            coupon = couponRepository.findByCodeForUpdate(request.couponCode())
                    .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND,
                            "쿠폰을 찾을 수 없습니다: code=" + request.couponCode()));
        }

        // 6. 재고 검사 (요청 항목 순서)
        for (CreateOrderRequest.Item item : items) {
            Product p = products.get(item.productId());
            if (p.available() < item.quantity()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "상품 " + p.getId() + "의 가용 재고("
                        + p.available() + ")가 요청 수량(" + item.quantity() + ")보다 적습니다.");
            }
        }

        // 7. 소계
        long subtotal = 0;
        for (CreateOrderRequest.Item item : items) {
            subtotal = Math.addExact(subtotal,
                    Math.multiplyExact(products.get(item.productId()).getPrice(), (long) item.quantity()));
        }

        // 8. 쿠폰 판정
        long discount = 0;
        if (coupon != null) {
            checkCouponApplicable(coupon, userId, subtotal, now);
            discount = CouponPolicy.discount(coupon, subtotal);
        }

        // 9. 반영
        for (CreateOrderRequest.Item item : items) {
            products.get(item.productId()).reserve(item.quantity());
        }
        if (coupon != null) {
            coupon.use();
        }
        Order order = Order.create(userId, coupon == null ? null : coupon.getCode(), subtotal, discount,
                now, now.plus(properties.paymentTtl()).truncatedTo(ChronoUnit.MICROS));
        for (int i = 0; i < items.size(); i++) {
            CreateOrderRequest.Item item = items.get(i);
            order.addItem(i, item.productId(), item.quantity(), products.get(item.productId()).getPrice());
        }
        try {
            orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("ux_orders_user_coupon_active")) {
                throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE, "이미 이 쿠폰을 사용 중인 주문이 있습니다.");
            }
            throw e;
        }

        // 10. 멱등 레코드 완료 (같은 트랜잭션)
        return idempotency.complete(idempotencyRecordId, 201, OrderResponse.from(order), "/api/orders/" + order.getId());
    }

    private void checkCouponApplicable(Coupon coupon, String userId, long subtotal, Instant now) {
        if (!coupon.isWithinPeriod(now)) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE, "쿠폰 사용 가능 기간이 아닙니다.");
        }
        if (subtotal < coupon.getMinOrderAmount()) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE, "최소 주문 금액에 미달합니다.");
        }
        if (orderRepository.existsActiveCouponUse(userId, coupon.getCode(), OrderStatus.COUPON_ACTIVE)) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE, "이미 이 쿠폰을 사용 중인 주문이 있습니다.");
        }
        if (coupon.isExhausted()) {
            throw new ApiException(ErrorCode.COUPON_EXHAUSTED, "쿠폰이 모두 소진되었습니다.");
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: id=" + id));
    }

    @Transactional(readOnly = true)
    public OrderPageResponse list(String userId, OrderStatus status, int size, String cursor) {
        CursorCodec.Cursor decoded = cursor == null ? null : CursorCodec.decode(cursor);

        Specification<Order> spec = (root, query, cb) -> cb.conjunction();
        if (userId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("userId"), userId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (decoded != null) {
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.lessThan(root.<Instant>get("createdAt"), decoded.createdAt()),
                    cb.and(cb.equal(root.<Instant>get("createdAt"), decoded.createdAt()),
                            cb.lessThan(root.<Long>get("id"), decoded.id()))));
        }
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        List<Order> fetched = orderRepository.findBy(spec, q -> q.sortBy(sort).limit(size + 1).all());

        boolean hasNext = fetched.size() > size;
        List<Order> page = hasNext ? fetched.subList(0, size) : fetched;
        String nextCursor = null;
        if (hasNext) {
            Order last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(last.getCreatedAt(), last.getId());
        }
        return new OrderPageResponse(page.stream().map(OrderResponse::from).toList(), nextCursor);
    }
}
