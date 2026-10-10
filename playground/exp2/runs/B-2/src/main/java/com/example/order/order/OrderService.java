package com.example.order.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.order.common.Times;
import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderItemRequest;
import com.example.order.order.dto.OrderPageResponse;
import com.example.order.order.dto.OrderResponse;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;
    private final OrderProperties properties;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
                        CouponRepository couponRepository, OrderProperties properties) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
        this.properties = properties;
    }

    /**
     * 상품·쿠폰 행을 잠근 뒤 검사하고 예약한다. 검사 순서는 404(상품·쿠폰) → 409(재고 → 쿠폰)이다(C3).
     */
    @Transactional
    public OrderResponse create(String userId, CreateOrderRequest request) {
        Instant now = Times.now();

        List<Long> productIds = request.items().stream().map(OrderItemRequest::productId).toList();
        Map<Long, Product> products = productRepository.findAllByIdForUpdate(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (Long productId : productIds) {
            if (!products.containsKey(productId)) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "상품을 찾을 수 없습니다: id=" + productId);
            }
        }
        Coupon coupon = request.couponCode() == null ? null
                : couponRepository.findWithLockByCode(request.couponCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND,
                        "쿠폰을 찾을 수 없습니다: code=" + request.couponCode()));

        for (OrderItemRequest item : request.items()) {
            Product product = products.get(item.productId());
            if (product.available() < item.quantity()) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                        "재고가 부족합니다: productId=%d, available=%d, requested=%d"
                                .formatted(product.getId(), product.available(), item.quantity()));
            }
        }

        Order order = new Order(userId, now, now.plus(properties.paymentTtl()));
        for (OrderItemRequest item : request.items()) {
            order.addItem(item.productId(), item.quantity(), products.get(item.productId()).getPrice());
        }
        if (coupon != null) {
            checkCouponApplicable(coupon, userId, order.getSubtotal(), now);
            order.applyCoupon(coupon.getCode(), coupon.discountFor(order.getSubtotal()));
            coupon.use();
        }
        for (OrderItemRequest item : request.items()) {
            products.get(item.productId()).reserve(item.quantity());
        }
        return OrderResponse.from(orderRepository.save(order));
    }

    private void checkCouponApplicable(Coupon coupon, String userId, long subtotal, Instant now) {
        if (!coupon.isValidAt(now)) {
            throw notApplicable(coupon, "유효 기간이 아닙니다");
        }
        if (subtotal < coupon.getMinOrderAmount()) {
            throw notApplicable(coupon, "최소 주문 금액(%d)에 못 미칩니다".formatted(coupon.getMinOrderAmount()));
        }
        // 쿠폰 행을 잠근 상태라 같은 사용자의 동시 요청도 여기서 직렬화된다.
        if (orderRepository.existsByUserIdAndCouponCodeAndStatusIn(userId, coupon.getCode(), OrderStatus.COUPON_IN_USE)) {
            throw notApplicable(coupon, "이미 이 쿠폰을 사용 중인 주문이 있습니다");
        }
        if (coupon.isExhausted()) {
            throw new BusinessException(ErrorCode.COUPON_EXHAUSTED, "쿠폰이 모두 소진되었습니다: code=" + coupon.getCode());
        }
    }

    private static BusinessException notApplicable(Coupon coupon, String reason) {
        return new BusinessException(ErrorCode.COUPON_NOT_APPLICABLE,
                "쿠폰을 적용할 수 없습니다(%s): code=%s".formatted(reason, coupon.getCode()));
    }

    public OrderResponse get(long id) {
        return OrderResponse.from(orderRepository.findById(id).orElseThrow(() -> notFound(id)));
    }

    /** 키셋 페이지네이션: (createdAt, id) 내림차순으로 커서 다음부터 size건. */
    public OrderPageResponse list(String userId, OrderStatus status, int size, String cursor) {
        OrderCursor after = cursor == null || cursor.isEmpty() ? null : OrderCursor.decode(cursor);
        Specification<Order> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (after != null) {
                predicates.add(cb.or(
                        cb.lessThan(root.get("createdAt"), after.createdAt()),
                        cb.and(cb.equal(root.get("createdAt"), after.createdAt()), cb.lessThan(root.get("id"), after.id()))));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        List<Order> fetched = orderRepository.findBy(spec, q -> q.sortBy(NEWEST_FIRST).limit(size + 1).all());
        boolean hasNext = fetched.size() > size;
        List<Order> page = hasNext ? fetched.subList(0, size) : fetched;
        String nextCursor = hasNext ? OrderCursor.of(page.getLast()).encode() : null;
        return new OrderPageResponse(page.stream().map(OrderResponse::from).toList(), nextCursor);
    }

    @Transactional
    public OrderResponse ship(long id) {
        Order order = orderRepository.findWithLockById(id).orElseThrow(() -> notFound(id));
        order.ship();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse deliver(long id) {
        Order order = orderRepository.findWithLockById(id).orElseThrow(() -> notFound(id));
        order.deliver();
        return OrderResponse.from(order);
    }

    static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: id=" + id);
    }
}
