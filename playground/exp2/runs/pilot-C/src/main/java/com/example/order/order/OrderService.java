package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.product.Product;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 주문 생성 / 취소·환불 / 배송 / 만료. 각 public 메서드가 하나의 업무 트랜잭션이다. */
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final OrderInventory inventory;
    private final PaymentGatewayClient pgClient;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, CouponRepository couponRepository, OrderInventory inventory,
                        PaymentGatewayClient pgClient, OrderProperties properties, Clock clock) {
        this.orderRepository = orderRepository;
        this.couponRepository = couponRepository;
        this.inventory = inventory;
        this.pgClient = pgClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse create(String userId, CreateOrderRequest req) {
        OffsetDateTime now = Times.now(clock);

        // 상품 락: productId 오름차순 (inventory.lockProducts 는 order by id)
        List<Long> ids = req.items().stream().map(CreateOrderRequest.Item::productId).toList();
        Map<Long, Product> products = inventory.lockProducts(ids);
        if (products.size() < ids.size()) {
            List<Long> missing = ids.stream().filter(i -> !products.containsKey(i)).sorted().toList();
            throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "product(s) not found: " + missing);
        }

        Coupon coupon = null;
        if (req.couponCode() != null) {
            coupon = couponRepository.lockByCode(req.couponCode()).orElseThrow(
                    () -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "coupon " + req.couponCode() + " not found"));
        }

        // 재고 검사
        for (CreateOrderRequest.Item item : req.items()) {
            Product p = products.get(item.productId());
            if (p.available() < item.quantity()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "product " + p.getId() + ": available "
                        + p.available() + ", requested " + item.quantity());
            }
        }

        long subtotal = 0;
        for (CreateOrderRequest.Item item : req.items()) {
            subtotal = Math.addExact(subtotal, Math.multiplyExact(products.get(item.productId()).getPrice(),
                    (long) item.quantity()));
        }

        long discount = 0;
        if (coupon != null) {
            if (now.isBefore(coupon.getValidFrom()) || !now.isBefore(coupon.getValidUntil())) {
                throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE, "coupon is outside its validity period");
            }
            if (subtotal < coupon.getMinOrderAmount()) {
                throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                        "subtotal is below the coupon's minimum order amount");
            }
            if (orderRepository.existsInUse(coupon.getCode(), userId, OrderStatus.COUPON_IN_USE)) {
                throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE, "coupon is already in use by this user");
            }
            if (coupon.getUsedCount() >= coupon.getTotalQuantity()) {
                throw new ApiException(ErrorCode.COUPON_EXHAUSTED, "coupon has no remaining quantity");
            }
            discount = coupon.discountFor(subtotal);
        }

        OffsetDateTime expiresAt = now.plus(properties.paymentTtl()).truncatedTo(ChronoUnit.MICROS);
        OrderEntity order = new OrderEntity(userId, coupon == null ? null : coupon.getCode(), subtotal, discount,
                subtotal - discount, now, Times.normalize(expiresAt));
        for (CreateOrderRequest.Item item : req.items()) {
            Product p = products.get(item.productId());
            p.reserve(item.quantity());
            order.addItem(p.getId(), item.quantity(), p.getPrice());
        }
        if (coupon != null) {
            coupon.use();
        }
        orderRepository.saveAndFlush(order);
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse cancel(long id) {
        OrderEntity order = lockOrder(id);
        OffsetDateTime now = Times.now(clock);
        switch (order.getStatus()) {
            case PENDING_PAYMENT -> {
                if (!now.isBefore(order.getExpiresAt())) {
                    throw new ApiException(ErrorCode.INVALID_STATE, "order payment window has expired");
                }
                inventory.releaseReservation(order);
                inventory.restoreCoupon(order);
                order.transitionTo(OrderStatus.CANCELLED);
            }
            case PAID -> {
                if (order.getPaymentId() != null) {
                    pgClient.refund(order.getPaymentId()); // 장애 시 예외 -> 롤백 -> 503
                }
                inventory.restoreStock(order);
                inventory.restoreCoupon(order);
                order.transitionTo(OrderStatus.REFUNDED);
            }
            default -> throw invalidState(order);
        }
        orderRepository.flush();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse ship(long id) {
        return advance(id, OrderStatus.PAID, OrderStatus.SHIPPED);
    }

    @Transactional
    public OrderResponse deliver(long id) {
        return advance(id, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    private OrderResponse advance(long id, OrderStatus from, OrderStatus to) {
        OrderEntity order = lockOrder(id);
        if (order.getStatus() != from) {
            throw invalidState(order);
        }
        order.transitionTo(to);
        return OrderResponse.from(order);
    }

    /** 만료 스윕: 주문 하나당 트랜잭션 하나. 이미 처리됐거나 락 중이면 false. */
    @Transactional
    public boolean expireIfDue(long id) {
        OffsetDateTime now = Times.now(clock);
        var locked = orderRepository.lockDueSkipLocked(id, now);
        if (locked.isEmpty()) {
            return false;
        }
        OrderEntity order = locked.get();
        inventory.releaseReservation(order);
        inventory.restoreCoupon(order);
        order.transitionTo(OrderStatus.EXPIRED);
        orderRepository.flush();
        return true;
    }

    private OrderEntity lockOrder(long id) {
        return orderRepository.lockById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "order " + id + " not found"));
    }

    private static ApiException invalidState(OrderEntity order) {
        return new ApiException(ErrorCode.INVALID_STATE, "order " + order.getId() + " is " + order.getStatus());
    }
}
