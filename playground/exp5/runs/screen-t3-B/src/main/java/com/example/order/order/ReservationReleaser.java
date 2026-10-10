package com.example.order.order;

import com.example.order.coupon.CouponRepository;
import com.example.order.point.PointRepository;
import com.example.order.product.ProductRepository;
import java.util.Comparator;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Stock/coupon/point side effects of order transitions. Product rows are always touched in ascending id order, then the
 * coupon row, then the user's point account, matching the lock order used by order creation, so concurrent
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

    /**
     * PENDING_PAYMENT -> CANCELLED / EXPIRED / PAYMENT_FAILED: drop reservations, give the coupon use and the points
     * back.
     */
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

    /** A refund returns units to stock; a refund that closes the order also restores the coupon use (R2.6). */
    public void returnStock(Order order, Map<Long, Long> quantities, boolean closesOrder) {
        quantities.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> products.addStock(e.getKey(), e.getValue()));
        if (closesOrder) {
            restoreCoupon(order);
        }
    }

    /** Refund share that is given back as points. */
    public void returnPoints(Order order, long amount) {
        if (amount > 0) {
            points.add(order.userId(), amount);
        }
    }

    private void restoreCoupon(Order order) {
        if (order.couponCode() != null) {
            coupons.addUsed(order.couponCode(), -1);
        }
    }
}
