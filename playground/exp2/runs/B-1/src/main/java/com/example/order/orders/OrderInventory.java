package com.example.order.orders;

import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 주문이 잡고 있는 재고 예약·쿠폰 사용을 바꾼다. 호출자의 트랜잭션 안에서만 쓴다.
 * <p>
 * 잠금 순서는 항상 주문 → 상품(id 오름차순) → 쿠폰이다. 모든 경로가 이 순서를 지켜 교착을 피한다.
 */
@Component
class OrderInventory {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    OrderInventory(ProductRepository productRepository, CouponRepository couponRepository) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
    }

    /** 상품을 id 오름차순으로 잠가 id → 상품으로 돌려준다. 없는 id 는 결과에 빠진다. */
    Map<Long, Product> lockProducts(Collection<Long> productIds) {
        Map<Long, Product> products = new LinkedHashMap<>();
        for (Product product : productRepository.findAllByIdForUpdate(new TreeSet<>(productIds))) {
            products.put(product.getId(), product);
        }
        return products;
    }

    /** 결제 대기 주문이 끝남(취소·만료·결제 거절): 예약과 쿠폰 사용을 복원한다. */
    void releaseReservation(Order order) {
        Map<Long, Product> products = lockProducts(productIdsOf(order));
        order.getLines().forEach(line -> products.get(line.getProductId()).release(line.getQuantity()));
        restoreCoupon(order);
    }

    /** 결제 승인: 예약분을 판매로 확정한다. */
    void commitSale(Order order) {
        Map<Long, Product> products = lockProducts(productIdsOf(order));
        order.getLines().forEach(line -> products.get(line.getProductId()).commitSale(line.getQuantity()));
    }

    /** 환불: 판매분을 재고로 되돌리고 쿠폰 사용을 복원한다. */
    void restock(Order order) {
        Map<Long, Product> products = lockProducts(productIdsOf(order));
        order.getLines().forEach(line -> products.get(line.getProductId()).restock(line.getQuantity()));
        restoreCoupon(order);
    }

    private void restoreCoupon(Order order) {
        if (order.getCouponCode() == null) {
            return;
        }
        Coupon coupon = couponRepository.findByCodeForUpdate(order.getCouponCode())
                .orElseThrow(() -> new IllegalStateException("coupon not found: " + order.getCouponCode()));
        coupon.restore();
    }

    private static Collection<Long> productIdsOf(Order order) {
        return order.getLines().stream().map(OrderLine::getProductId).toList();
    }
}
