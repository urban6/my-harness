package com.example.order.order;

import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.util.Comparator;
import java.util.function.ObjIntConsumer;
import org.springframework.stereotype.Component;

/**
 * 주문 상태 전이에 따른 재고·쿠폰 반영.
 * <p>
 * 잠금 순서는 모든 경로에서 주문 → 상품(id 오름차순) → 쿠폰 이다. 순서를 지켜 교착을 피한다.
 * 호출하는 쪽은 트랜잭션 안에서 주문 행을 이미 잠근 상태여야 한다.
 */
@Component
public class OrderInventory {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    public OrderInventory(ProductRepository productRepository, CouponRepository couponRepository) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
    }

    /** 결제 대기가 끝나지 못함(취소·만료·결제 거절): 예약과 쿠폰 사용을 복원한다. */
    public void releaseReservation(Order order) {
        forEachProduct(order, Product::release);
        restoreCoupon(order);
    }

    /** 결제 승인: 예약 수량이 판매되어 재고와 예약이 함께 줄어든다. */
    public void sellReserved(Order order) {
        forEachProduct(order, Product::sellReserved);
    }

    /** 환불: 판매된 수량을 재고로 돌리고 쿠폰 사용을 복원한다. */
    public void restockAndRestoreCoupon(Order order) {
        forEachProduct(order, Product::restock);
        restoreCoupon(order);
    }

    private void forEachProduct(Order order, ObjIntConsumer<Product> action) {
        order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getProductId))
                .forEach(item -> {
                    Product product = productRepository.findByIdForUpdate(item.getProductId())
                            .orElseThrow(() -> new IllegalStateException("product " + item.getProductId() + " missing"));
                    action.accept(product, item.getQuantity());
                });
    }

    private void restoreCoupon(Order order) {
        if (order.getCouponCode() == null) {
            return;
        }
        Coupon coupon = couponRepository.findByCodeForUpdate(order.getCouponCode())
                .orElseThrow(() -> new IllegalStateException("coupon " + order.getCouponCode() + " missing"));
        coupon.restore();
    }
}
