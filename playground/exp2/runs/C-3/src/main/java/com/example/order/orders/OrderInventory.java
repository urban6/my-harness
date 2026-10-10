package com.example.order.orders;

import com.example.order.coupon.CouponRepository;
import com.example.order.product.ProductRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 주문 상태 전이에 따른 재고·쿠폰 카운터 변경(전부 네이티브 UPDATE). 호출자의 트랜잭션 안에서 실행된다.
 * 상품은 항상 productId 오름차순으로 갱신해 잠금 순서를 고정한다.
 */
@Component
class OrderInventory {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    OrderInventory(ProductRepository productRepository, CouponRepository couponRepository) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
    }

    /** 거절·만료·취소: reserved -= q */
    void releaseReservation(Order order) {
        for (OrderItem i : sorted(order)) {
            productRepository.releaseReservation(i.getProductId(), i.getQuantity());
        }
    }

    /** 결제 승인: stock -= q, reserved -= q */
    void commitReservation(Order order) {
        for (OrderItem i : sorted(order)) {
            productRepository.commitReservation(i.getProductId(), i.getQuantity());
        }
    }

    /** 환불: stock += q */
    void restock(Order order) {
        for (OrderItem i : sorted(order)) {
            productRepository.restock(i.getProductId(), i.getQuantity());
        }
    }

    /** 쿠폰 사용 복원 (쿠폰이 있을 때만) */
    void releaseCoupon(Order order) {
        if (order.getCouponCode() != null) {
            couponRepository.releaseUse(order.getCouponCode());
        }
    }

    private static List<OrderItem> sorted(Order order) {
        return order.getItems().stream().sorted(Comparator.comparing(OrderItem::getProductId)).toList();
    }
}
