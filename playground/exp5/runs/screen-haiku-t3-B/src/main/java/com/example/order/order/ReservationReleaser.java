package com.example.order.order;

import com.example.order.coupon.CouponRepository;
import com.example.order.point.PointRepository;
import com.example.order.product.ProductRepository;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Stock, coupon and point side effects of order transitions. Product rows are always touched in ascending id order,
 * then the coupon row, then the point balance, matching the lock order used by order creation, so concurrent
 * transitions cannot deadlock.
 */
@Component
public class ReservationReleaser {

    private final ProductRepository products;
    private final CouponRepository coupons;
    private final PointRepository points;

    public ReservationReleaser(ProductRepository products, CouponRepository coupons, PointRepository points) {
        this.products = products;
        this.coupons = coupons;
        this.points = points;
    }

    /** PENDING_PAYMENT -> CANCELLED / EXPIRED / PAYMENT_FAILED: drop reservations, give the coupon use and points back. */
    public void releaseReservation(Order order) {
        order.items().stream().sorted(Comparator.comparingLong(Order.Item::productId))
                .forEach(i -> products.addReserved(i.productId(), -i.quantity()));
        restoreCoupon(order);
        if (order.pointAmount() > 0) {
            points.add(order.userId(), order.pointAmount());
        }
    }

    /** PENDING_PAYMENT -> PAID: reserved units are sold. */
    public void consumeReservation(Order order) {
        order.items().stream().sorted(Comparator.comparingLong(Order.Item::productId))
                .forEach(i -> products.consumeReserved(i.productId(), i.quantity()));
    }

    /** Refunded units go back to stock, in ascending product id order. */
    public void returnStock(Map<Long, Long> quantities) {
        new TreeMap<>(quantities).forEach((productId, quantity) -> products.addStock(productId, quantity));
    }

    /** An order that becomes REFUNDED gives its coupon use back (R2.6). */
    public void restoreCoupon(Order order) {
        if (order.couponCode() != null) {
            coupons.addUsed(order.couponCode(), -1);
        }
    }
}
