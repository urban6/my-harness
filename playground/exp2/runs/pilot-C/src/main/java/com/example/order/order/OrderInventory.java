package com.example.order.order;

import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 재고 예약/쿠폰 사용 복원 공통 루틴 (설계 3.3). 반드시 호출자의 트랜잭션 안에서 실행된다. */
@Component
public class OrderInventory {
    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    public OrderInventory(ProductRepository productRepository, CouponRepository couponRepository) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
    }

    /** 상품 행을 id 오름차순으로 락 걸고 id -> Product 맵으로 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Product> lockProducts(List<Long> ids) {
        return productRepository.lockAllByIdIn(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private Map<Long, Product> lockFor(OrderEntity order) {
        return lockProducts(order.getItems().stream().map(OrderItem::getProductId).toList());
    }

    /** PENDING 에서 벗어날 때 (CANCELLED, EXPIRED, PAYMENT_FAILED): reserved 감소. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseReservation(OrderEntity order) {
        Map<Long, Product> products = lockFor(order);
        order.getItems().forEach(i -> products.get(i.getProductId()).release(i.getQuantity()));
    }

    /** 결제 승인: stock, reserved 감소. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sell(OrderEntity order) {
        Map<Long, Product> products = lockFor(order);
        order.getItems().forEach(i -> products.get(i.getProductId()).sell(i.getQuantity()));
    }

    /** 환불: stock 증가. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreStock(OrderEntity order) {
        Map<Long, Product> products = lockFor(order);
        order.getItems().forEach(i -> products.get(i.getProductId()).restock(i.getQuantity()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreCoupon(OrderEntity order) {
        if (order.getCouponCode() == null) {
            return;
        }
        Coupon c = couponRepository.lockByCode(order.getCouponCode()).orElseThrow();
        c.restore();
    }
}
