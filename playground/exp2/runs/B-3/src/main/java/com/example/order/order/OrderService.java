package com.example.order.order;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import com.example.order.coupon.Coupon;
import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderItemRequest;
import com.example.order.order.dto.OrderPageResponse;
import com.example.order.order.dto.OrderResponse;
import com.example.order.product.Product;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final ReservationService reservationService;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, ReservationService reservationService,
                        OrderProperties properties, Clock clock) {
        this.orderRepository = orderRepository;
        this.reservationService = reservationService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 주문을 만들고 재고를 예약한다. 검사 순서는 404(상품·쿠폰) → 409(재고 → 쿠폰)이며,
     * 하나라도 실패하면 트랜잭션 전체가 롤백되어 예약·쿠폰 사용이 반영되지 않는다.
     */
    @Transactional
    public OrderResponse create(String userId, CreateOrderRequest request) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Map<Long, Product> products = reservationService.lockProducts(
                request.items().stream().map(OrderItemRequest::productId).toList());
        Coupon coupon = request.couponCode() == null ? null : reservationService.lockCoupon(request.couponCode());

        Order order = new Order(userId, request.couponCode(), now, now.plus(properties.paymentTtl()));
        for (OrderItemRequest item : request.items()) {
            Product product = products.get(item.productId());
            product.reserve(item.quantity());
            order.addItem(product.getId(), item.quantity(), product.getPrice());
        }

        if (coupon != null) {
            applyCoupon(order, coupon, now);
        }
        return OrderResponse.from(orderRepository.save(order));
    }

    private void applyCoupon(Order order, Coupon coupon, Instant now) {
        boolean applicable = coupon.isValidAt(now)
                && order.getSubtotal() >= coupon.getMinOrderAmount()
                && !orderRepository.existsByUserIdAndCouponCodeAndStatusIn(
                        order.getUserId(), coupon.getCode(), OrderStatus.HOLDING_COUPON);
        if (!applicable) {
            throw new BusinessException(ErrorCode.COUPON_NOT_APPLICABLE, "쿠폰을 사용할 수 없는 주문입니다: " + coupon.getCode());
        }
        if (coupon.isExhausted()) {
            throw new BusinessException(ErrorCode.COUPON_EXHAUSTED, "쿠폰이 모두 소진되었습니다: " + coupon.getCode());
        }
        coupon.use();
        order.applyDiscount(coupon.discountFor(order.getSubtotal()));
    }

    public OrderResponse get(Long id) {
        return OrderResponse.from(orderRepository.findById(id).orElseThrow(() -> notFound(id)));
    }

    public OrderPageResponse list(String userId, OrderStatus status, int size, String cursor) {
        OrderCursor after = cursor == null ? null : OrderCursor.decode(cursor);
        List<Order> rows = orderRepository.findPage(userId, status, after, size + 1);
        boolean hasNext = rows.size() > size;
        List<Order> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext ? OrderCursor.of(page.getLast()).encode() : null;
        return new OrderPageResponse(page.stream().map(OrderResponse::from).toList(), nextCursor);
    }

    public static BusinessException notFound(Long id) {
        return new BusinessException(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: id=" + id);
    }
}
