package com.example.order.order;

import com.example.order.coupon.CouponRepository;
import com.example.order.product.ProductRepository;
import java.util.Comparator;
import org.springframework.stereotype.Component;

/**
 * Stock/coupon side effects of order transitions. Product rows are always touched in ascending id order and the coupon
 * row last, matching the lock order used by order creation, so concurrent transitions cannot deadlock.
 */
@Component
public class ReservationReleaser {

    private final ProductRepository products;
    private final CouponRepository coupons;

    public ReservationReleaser(ProductRepository products, CouponRepository coupons) {
        this.products = products;
        this.coupons = coupons;
    }

    /** PENDING_PAYMENT -> CANCELLED / EXPIRED / PAYMENT_FAILED: drop reservations, give the coupon use back. */
    public void releaseReservation(Order order) {
        order.items().stream().sorted(Comparator.comparingLong(Order.Item::productId))
                .forEach(i -> products.addReserved(i.productId(), -i.quantity()));
        restoreCoupon(order);
    }

    /** PENDING_PAYMENT -> PAID: reserved units are sold. */
    public void consumeReservation(Order order) {
        order.items().stream().sorted(Comparator.comparingLong(Order.Item::productId))
                .forEach(i -> products.consumeReserved(i.productId(), i.quantity()));
    }

    /** PAID -> REFUNDED: units go back to stock, coupon use is restored. */
    public void returnStock(Order order) {
        order.items().stream().sorted(Comparator.comparingLong(Order.Item::productId))
                .forEach(i -> products.addStock(i.productId(), i.quantity()));
        restoreCoupon(order);
    }

    private void restoreCoupon(Order order) {
        if (order.couponCode() != null) {
            coupons.addUsed(order.tenantId(), order.couponCode(), -1);
        }
    }
}
