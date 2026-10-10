package com.example.order.order;

import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 호출자 트랜잭션 안에서 상품·쿠폰 수량을 변경하는 공통 로직 (MANDATORY).
 * 락 순서 규칙: 주문(호출자가 이미 잠금) → 상품(id 오름차순) → 쿠폰.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OrderOperations {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    public OrderOperations(ProductRepository productRepository, CouponRepository couponRepository) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
    }

    /** PENDING_PAYMENT → EXPIRED. 예약·쿠폰 복원. */
    public void expire(OrderEntity order, Instant now) {
        applyToProducts(order, (p, q) -> p.release(q));
        restoreCoupon(order);
        order.changeStatus(OrderStatus.EXPIRED, now);
    }

    /** PENDING_PAYMENT → CANCELLED. */
    public void cancelPending(OrderEntity order, Instant now) {
        applyToProducts(order, (p, q) -> p.release(q));
        restoreCoupon(order);
        order.changeStatus(OrderStatus.CANCELLED, now);
    }

    /** PENDING_PAYMENT → PAYMENT_FAILED. */
    public void failPayment(OrderEntity order, Instant now) {
        applyToProducts(order, (p, q) -> p.release(q));
        restoreCoupon(order);
        order.changeStatus(OrderStatus.PAYMENT_FAILED, now);
    }

    /** 결제 승인: stock, reserved 각각 −q. 상태 전이는 호출자가 markPaid 로. */
    public void commitSale(OrderEntity order) {
        applyToProducts(order, (p, q) -> p.commitSale(q));
    }

    /** PAID → REFUNDED: stock +q, 쿠폰 복원 (reserved 불변). */
    public void refund(OrderEntity order, Instant now) {
        applyToProducts(order, (p, q) -> p.restock(q));
        restoreCoupon(order);
        order.changeStatus(OrderStatus.REFUNDED, now);
    }

    private void applyToProducts(OrderEntity order, BiConsumer<Product, Integer> action) {
        List<Long> ids = order.getItems().stream().map(OrderItem::getProductId).sorted().toList();
        Map<Long, Product> products = productRepository.lockAllByIdIn(ids).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        for (OrderItem item : order.getItems()) {
            action.accept(products.get(item.getProductId()), item.getQuantity());
        }
    }

    private void restoreCoupon(OrderEntity order) {
        if (order.getCouponId() == null) {
            return;
        }
        Coupon coupon = couponRepository.lockById(order.getCouponId()).orElseThrow();
        coupon.restore();
    }
}
