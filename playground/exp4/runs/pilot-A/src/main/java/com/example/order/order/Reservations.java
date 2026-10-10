package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 상태 전이에 따른 재고·쿠폰 반영. 항상 (이미 잠근 주문) → 상품(id 오름차순) → 쿠폰 순으로 잠가
 * 주문 생성(상품 → 쿠폰)과 같은 순서를 지킨다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class Reservations {

    private final ProductRepository products;
    private final CouponRepository coupons;

    public Reservations(ProductRepository products, CouponRepository coupons) {
        this.products = products;
        this.coupons = coupons;
    }

    /** 결제 대기 주문이 사라질 때(실패·만료·취소): 예약과 쿠폰 사용을 되돌린다. */
    public void release(PurchaseOrder order) {
        forEachProduct(order, (p, qty) -> p.release(qty));
        restoreCoupon(order);
    }

    /** 결제 승인: 판매 확정. */
    public void sell(PurchaseOrder order) {
        forEachProduct(order, (p, qty) -> p.sell(qty));
    }

    /** 환불: 재고를 되돌리고 쿠폰 사용을 복원한다. */
    public void refund(PurchaseOrder order) {
        forEachProduct(order, (p, qty) -> p.restock(qty));
        restoreCoupon(order);
    }

    private void forEachProduct(PurchaseOrder order, java.util.function.BiConsumer<Product, Long> action) {
        List<OrderItem> sorted = order.getItems().stream()
                .sorted(Comparator.comparingLong(OrderItem::getProductId)).toList();
        for (OrderItem item : sorted) {
            Product product = products.findByIdForUpdate(item.getProductId())
                    .orElseThrow(() -> new IllegalStateException("Product vanished: " + item.getProductId()));
            action.accept(product, (long) item.getQuantity());
        }
    }

    private void restoreCoupon(PurchaseOrder order) {
        if (order.getCouponId() == null) {
            return;
        }
        Coupon coupon = coupons.findByIdForUpdate(order.getCouponId())
                .orElseThrow(() -> new IllegalStateException("Coupon vanished: " + order.getCouponId()));
        coupon.restore();
    }

    static ApiException invalidState(PurchaseOrder order, String action) {
        return new ApiException(ErrorCode.INVALID_STATE,
                "Cannot " + action + " order " + order.getId() + " in status " + order.getStatus());
    }
}
