package com.example.order.order;

import com.example.order.coupon.CouponUsageRepository;
import com.example.order.product.StockRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Gives back reserved stock and the coupon quantity. Must be called only inside the transaction whose
 * conditional UPDATE just won the claim (so it happens exactly once). Lock order: products(id asc) -> coupons.
 */
@Component
public class ReservationReleaser {

    private final OrderRepository orders;
    private final StockRepository stock;
    private final CouponUsageRepository couponUsage;

    public ReservationReleaser(OrderRepository orders, StockRepository stock, CouponUsageRepository couponUsage) {
        this.orders = orders;
        this.stock = stock;
        this.couponUsage = couponUsage;
    }

    public void release(long orderId, Instant now) {
        OrderRow order = orders.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order vanished during release: " + orderId));
        for (OrderItemRow item : sortedItems(orderId)) {
            if (!stock.release(item.productId(), item.quantity(), now)) {
                throw new IllegalStateException("Stock invariant violated releasing product " + item.productId()
                        + " for order " + orderId);
            }
        }
        if (order.couponId() != null && !couponUsage.release(order.couponId())) {
            throw new IllegalStateException("Coupon invariant violated releasing coupon " + order.couponId()
                    + " for order " + orderId);
        }
    }

    public List<OrderItemRow> sortedItems(long orderId) {
        return orders.findItems(List.of(orderId)).stream()
                .sorted(Comparator.comparingLong(OrderItemRow::productId)).toList();
    }
}
