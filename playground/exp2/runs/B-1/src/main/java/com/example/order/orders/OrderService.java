package com.example.order.orders;

import com.example.order.common.Times;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponExhaustedException;
import com.example.order.coupon.CouponNotApplicableException;
import com.example.order.coupon.CouponNotFoundException;
import com.example.order.coupon.CouponRepository;
import com.example.order.orders.dto.CreateOrderRequest;
import com.example.order.orders.dto.OrderItemRequest;
import com.example.order.orders.dto.OrderPageResponse;
import com.example.order.orders.dto.OrderResponse;
import com.example.order.product.InsufficientStockException;
import com.example.order.product.Product;
import com.example.order.product.ProductNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final OrderInventory inventory;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, CouponRepository couponRepository,
                        OrderInventory inventory, OrderProperties properties, Clock clock) {
        this.orderRepository = orderRepository;
        this.couponRepository = couponRepository;
        this.inventory = inventory;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 주문 생성(R3). 상품·쿠폰 행을 잠근 상태에서 404 → 재고 409 → 쿠폰 409 순으로 검사하고(C3),
     * 모두 통과할 때만 예약·쿠폰 사용을 한 트랜잭션으로 반영한다(R3.4).
     */
    @Transactional
    public OrderResponse create(String userId, CreateOrderRequest request) {
        Instant now = Times.now(clock);
        List<OrderItemRequest> items = request.items();

        Map<Long, Product> products = inventory.lockProducts(items.stream().map(OrderItemRequest::productId).toList());
        for (OrderItemRequest item : items) {
            if (!products.containsKey(item.productId())) {
                throw new ProductNotFoundException(item.productId());
            }
        }
        Coupon coupon = null;
        if (request.couponCode() != null) {
            coupon = couponRepository.findByCodeForUpdate(request.couponCode())
                    .orElseThrow(() -> new CouponNotFoundException(request.couponCode()));
        }

        for (OrderItemRequest item : items) {
            if (!products.get(item.productId()).canReserve(item.quantity())) {
                throw new InsufficientStockException(item.productId());
            }
        }

        List<OrderLine> lines = items.stream()
                .map(item -> new OrderLine(item.productId(), item.quantity(), products.get(item.productId()).getPrice()))
                .toList();
        long subtotal = Order.subtotalOf(lines);
        long discount = 0;
        if (coupon != null) {
            checkApplicable(coupon, userId, subtotal, now);
            discount = coupon.calculateDiscount(subtotal);
            coupon.use();
        }
        for (OrderItemRequest item : items) {
            products.get(item.productId()).reserve(item.quantity());
        }

        Order order = new Order(userId, lines, request.couponCode(), discount, now, now.plus(properties.paymentTtl()));
        return OrderResponse.from(orderRepository.save(order));
    }

    private void checkApplicable(Coupon coupon, String userId, long subtotal, Instant now) {
        if (!coupon.isValidAt(now)) {
            throw new CouponNotApplicableException("유효 기간이 아닙니다: code=" + coupon.getCode());
        }
        if (!coupon.meetsMinOrderAmount(subtotal)) {
            throw new CouponNotApplicableException("최소 주문 금액(" + coupon.getMinOrderAmount() + ")에 못 미칩니다");
        }
        if (orderRepository.existsActiveByUserIdAndCouponCode(userId, coupon.getCode())) {
            throw new CouponNotApplicableException("이미 이 쿠폰을 사용 중인 주문이 있습니다: code=" + coupon.getCode());
        }
        if (coupon.isExhausted()) {
            throw new CouponExhaustedException(coupon.getCode());
        }
    }

    public OrderResponse get(Long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    /** 주문 목록(R9): createdAt desc, id desc 키셋 페이지네이션. */
    public OrderPageResponse list(String userId, OrderStatus status, int size, String cursor) {
        OrderCursor after = cursor == null ? null : OrderCursor.decode(cursor);
        List<Order> rows = orderRepository.findPage(userId, status, after, size + 1);
        boolean hasNext = rows.size() > size;
        List<Order> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext ? OrderCursor.of(page.getLast()).encode() : null;
        return new OrderPageResponse(page.stream().map(OrderResponse::from).toList(), nextCursor);
    }
}
