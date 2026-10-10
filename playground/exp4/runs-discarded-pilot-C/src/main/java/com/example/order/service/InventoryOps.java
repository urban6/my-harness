package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.domain.OrderItem;
import com.example.order.repository.CouponRepository;
import com.example.order.repository.ProductRepository;
import org.springframework.stereotype.Component;

/**
 * 주문 상태 전이에 따른 재고·쿠폰 증감. 반드시 활성 트랜잭션 + 주문 행 락 안에서 호출한다.
 * 락 순서: 주문 -> 상품(id 오름차순) -> 쿠폰 (02 문서 6절). 카운터는 원자적 증감 UPDATE 만 쓴다.
 */
@org.springframework.stereotype.Service
public class InventoryOps {

    private final ProductRepository products;
    private final CouponRepository coupons;

    public InventoryOps(ProductRepository products, CouponRepository coupons) {
        this.products = products;
        this.coupons = coupons;
    }

    /** CANCELLED / EXPIRED / PAYMENT_FAILED: reserved -= q, 쿠폰 사용 복원. */
    public void releaseReservation(Order order) {
        for (OrderItem item : order.itemsByProductId()) {
            products.release(item.getProductId(), item.getQuantity());
        }
        restoreCoupon(order);
    }

    /** PAID 승인: stock -= q, reserved -= q. */
    public void confirmSale(Order order) {
        for (OrderItem item : order.itemsByProductId()) {
            products.confirmSale(item.getProductId(), item.getQuantity());
        }
    }

    /** REFUNDED: stock += q, 쿠폰 사용 복원. */
    public void restockAndRestoreCoupon(Order order) {
        for (OrderItem item : order.itemsByProductId()) {
            products.restock(item.getProductId(), item.getQuantity());
        }
        restoreCoupon(order);
    }

    private void restoreCoupon(Order order) {
        if (order.getCouponId() != null) {
            coupons.decreaseUsed(order.getCouponId());
        }
    }
}
