package com.example.order.order;

import com.example.order.coupon.CouponRepository;
import com.example.order.product.ProductRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Stock/coupon side effects of order state transitions (01 section 8). Must run inside the transition's
 * transaction, products in ascending id order first, then the coupon (lock order, 02 section 3.4).
 */
@Component
public class OrderInventory {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    public OrderInventory(ProductRepository productRepository, CouponRepository couponRepository) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
    }

    /** CANCELLED / EXPIRED / PAYMENT_FAILED from PENDING_PAYMENT: reserved -= q, coupon used_count -= 1. */
    public void release(PurchaseOrder order, List<OrderItem> items) {
        for (OrderItem item : sorted(items)) {
            if (productRepository.release(item.getProductId(), item.getQuantity()) != 1) {
                throw new IllegalStateException("Reservation invariant violated for product " + item.getProductId()
                        + " (order " + order.getId() + ")");
            }
        }
        if (order.getCouponCode() != null && couponRepository.restore(order.getCouponCode()) != 1) {
            throw new IllegalStateException("Coupon usage invariant violated for order " + order.getId());
        }
    }

    /** PAID: stock -= q, reserved -= q. Coupon stays consumed. */
    public void confirm(PurchaseOrder order, List<OrderItem> items) {
        for (OrderItem item : sorted(items)) {
            if (productRepository.confirm(item.getProductId(), item.getQuantity()) != 1) {
                throw new IllegalStateException("Stock confirm invariant violated for product " + item.getProductId()
                        + " (order " + order.getId() + ")");
            }
        }
    }

    /** REFUNDED: stock += q. Coupon is NOT restored (D-02). */
    public void restoreStock(PurchaseOrder order, List<OrderItem> items) {
        for (OrderItem item : sorted(items)) {
            if (productRepository.restoreStock(item.getProductId(), item.getQuantity()) != 1) {
                throw new IllegalStateException("Stock restore failed for product " + item.getProductId()
                        + " (order " + order.getId() + ")");
            }
        }
    }

    private static List<OrderItem> sorted(List<OrderItem> items) {
        return items.stream().sorted(Comparator.comparing(OrderItem::getProductId)).toList();
    }
}
