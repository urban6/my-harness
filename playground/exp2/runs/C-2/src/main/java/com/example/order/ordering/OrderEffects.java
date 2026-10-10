package com.example.order.ordering;

import com.example.order.common.error.ProductNotFoundException;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 주문 상태 전이에 따른 재고/쿠폰 부수 효과 (01 설계 13). 반드시 활성 트랜잭션 안에서,
 * 주문 행 락을 먼저 잡은 뒤 호출한다. 락 순서: orders -> products(id 오름차순) -> coupons.
 */
@Component
public class OrderEffects {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    public OrderEffects(ProductRepository productRepository, CouponRepository couponRepository) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
    }

    /** 상품을 id 오름차순으로 FOR UPDATE. 없는 id가 있으면 404. */
    public Map<Long, Product> lockProducts(Collection<Long> productIds) {
        List<Long> sorted = productIds.stream().distinct().sorted().toList();
        Map<Long, Product> byId = productRepository.findAllByIdInForUpdate(sorted).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Long> missing = sorted.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new ProductNotFoundException("상품을 찾을 수 없습니다: " + missing);
        }
        return byId;
    }

    private Map<Long, Product> lockProductsOf(Order order) {
        return lockProducts(order.getItems().stream().map(OrderLine::productId).toList());
    }

    /** 결제 승인: stock -= q, reserved -= q */
    public void confirmSale(Order order) {
        Map<Long, Product> products = lockProductsOf(order);
        order.getItems().forEach(l -> products.get(l.productId()).confirmSale(l.quantity()));
    }

    /** 예약 해제: reserved -= q (거절/만료/취소) */
    public void releaseReservation(Order order) {
        Map<Long, Product> products = lockProductsOf(order);
        order.getItems().forEach(l -> products.get(l.productId()).releaseReservation(l.quantity()));
    }

    /** 환불: stock += q */
    public void restock(Order order) {
        Map<Long, Product> products = lockProductsOf(order);
        order.getItems().forEach(l -> products.get(l.productId()).restock(l.quantity()));
    }

    /** 쿠폰 사용 복원: usedCount -= 1. 상품 락 뒤에 호출한다. */
    public void releaseCoupon(Order order) {
        if (order.getCouponCode() == null) {
            return;
        }
        Coupon coupon = couponRepository.findByCodeForUpdate(order.getCouponCode())
                .orElseThrow(() -> new IllegalStateException("coupon missing for order " + order.getId()));
        coupon.release();
    }
}
