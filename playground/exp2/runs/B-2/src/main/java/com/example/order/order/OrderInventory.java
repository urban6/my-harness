package com.example.order.order;

import java.util.List;
import java.util.Map;
import java.util.function.ObjIntConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 상태 변화에 따른 재고·쿠폰 반영. 잠금 순서는 항상 주문 → 상품(id 오름차순) → 쿠폰이다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OrderInventory {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    public OrderInventory(ProductRepository productRepository, CouponRepository couponRepository) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
    }

    public void releaseReservations(Order order) {
        apply(order, Product::releaseReservation);
    }

    public void confirmSale(Order order) {
        apply(order, Product::confirmSale);
    }

    public void restock(Order order) {
        apply(order, Product::restock);
    }

    public void restoreCoupon(Order order) {
        if (order.getCouponCode() == null) {
            return;
        }
        Coupon coupon = couponRepository.findWithLockByCode(order.getCouponCode())
                .orElseThrow(() -> new IllegalStateException("coupon not found: " + order.getCouponCode()));
        coupon.restore();
    }

    private void apply(Order order, ObjIntConsumer<Product> action) {
        List<Long> productIds = order.getItems().stream().map(OrderItem::getProductId).toList();
        Map<Long, Product> products = productRepository.findAllByIdForUpdate(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (OrderItem item : order.getItems()) {
            action.accept(products.get(item.getProductId()), item.getQuantity());
        }
    }
}
