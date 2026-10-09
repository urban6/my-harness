package com.example.order.order;

import com.example.order.common.error.ApiException;
import com.example.order.common.error.ErrorCode;
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
 * 재고·쿠폰 자원 조작 공통부. 반드시 호출자의 트랜잭션 안에서 사용한다.
 * 전역 락 순서(orders → products asc → coupon)를 지키도록 상품은 id 오름차순으로 하나씩 락한다.
 */
@Component
public class OrderResourceSupport {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    public OrderResourceSupport(ProductRepository productRepository, CouponRepository couponRepository) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
    }

    /** id 오름차순으로 FOR UPDATE. 없는 상품이 있으면 404. */
    public Map<Long, Product> lockProducts(Collection<Long> productIds) {
        Map<Long, Product> locked = new LinkedHashMap<>();
        for (Long id : new TreeSet<>(productIds)) {
            Product p = productRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "상품을 찾을 수 없습니다: id=" + id));
            locked.put(id, p);
        }
        return locked;
    }

    private Map<Long, Product> lockProductsOf(Order order) {
        return lockProducts(order.getItems().stream().map(OrderItem::getProductId).toList());
    }

    /** 취소·만료·결제 거절: 예약 복원 + 쿠폰 사용 복원. */
    public void releaseReservation(Order order) {
        Map<Long, Product> products = lockProductsOf(order);
        for (OrderItem item : order.getItems()) {
            products.get(item.getProductId()).release(item.getQuantity());
        }
        restoreCoupon(order);
    }

    /** 결제 승인: stock, reserved 동시 차감. */
    public void commitSale(Order order) {
        Map<Long, Product> products = lockProductsOf(order);
        for (OrderItem item : order.getItems()) {
            products.get(item.getProductId()).commitSale(item.getQuantity());
        }
    }

    /** 환불: stock 복원 + 쿠폰 사용 복원. */
    public void restock(Order order) {
        Map<Long, Product> products = lockProductsOf(order);
        for (OrderItem item : order.getItems()) {
            products.get(item.getProductId()).restock(item.getQuantity());
        }
        restoreCoupon(order);
    }

    public void restoreCoupon(Order order) {
        if (order.getCouponCode() == null) {
            return;
        }
        Coupon coupon = couponRepository.findByCodeForUpdate(order.getCouponCode())
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND,
                        "쿠폰을 찾을 수 없습니다: code=" + order.getCouponCode()));
        coupon.restore();
    }
}
