package com.example.order.order;

import com.example.order.coupon.CouponRepository;
import com.example.order.point.PointRepository;
import com.example.order.product.ProductRepository;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Stock/coupon/point side effects of order transitions. Product rows are always touched in ascending id order, then
 * the coupon row, then the point account, matching the lock order used by order creation, so concurrent transitions
 * cannot deadlock.
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

    /**
     * PENDING_PAYMENT -> CANCELLED / EXPIRED / PAYMENT_FAILED: drop reservations, give the coupon use and the points
     * back (R2.6, P2.6).
     */
    public void releaseReservation(Order order) {
        order.items().stream().sorted(Comparator.comparingLong(Order.Item::productId))
                .forEach(i -> products.addReserved(i.productId(), -i.quantity()));
        restoreCoupon(order);
        returnPoints(order.userId(), order.pointAmount());
    }

    /** PENDING_PAYMENT -> PAID: reserved units are sold. */
    public void consumeReservation(Order order) {
        order.items().stream().sorted(Comparator.comparingLong(Order.Item::productId))
                .forEach(i -> products.consumeReserved(i.productId(), i.quantity()));
    }

    /** P4.9: refunded units go back to stock (productId -> quantity). */
    public void returnStock(Map<Long, Long> quantities) {
        new TreeMap<>(quantities).forEach(products::addStock);
    }

    public void restoreCoupon(Order order) {
        if (order.couponCode() != null) {
            coupons.addUsed(order.couponCode(), -1);
        }
    }

    public void returnPoints(String userId, long amount) {
        if (amount > 0) {
            points.add(userId, amount);
        }
    }
}
