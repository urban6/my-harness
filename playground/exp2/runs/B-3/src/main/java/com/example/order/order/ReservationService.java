package com.example.order.order;

import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.coupon.CouponService;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import com.example.order.product.ProductService;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 예약·쿠폰 사용의 반영과 복원.
 *
 * <p>교착을 피하려고 락 순서를 고정한다: 주문 → 상품(id 오름차순) → 쿠폰.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ReservationService {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    public ReservationService(ProductRepository productRepository, CouponRepository couponRepository) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
    }

    /** 상품들을 id 오름차순으로 잠그고 돌려준다. 없으면 PRODUCT_NOT_FOUND. */
    public Map<Long, Product> lockProducts(Collection<Long> productIds) {
        Map<Long, Product> locked = new LinkedHashMap<>();
        productIds.stream().distinct().sorted().forEach(id -> locked.put(id,
                productRepository.findByIdForUpdate(id).orElseThrow(() -> ProductService.notFound(id))));
        return locked;
    }

    public Coupon lockCoupon(String code) {
        return couponRepository.findByCodeForUpdate(code).orElseThrow(() -> CouponService.notFound(code));
    }

    /** 결제 대기 주문의 예약과 쿠폰 사용을 복원한다(취소·만료·결제 거절). */
    public void releaseReservation(Order order) {
        forEachItem(order, Product::releaseReservation);
        restoreCoupon(order);
    }

    /** 예약분을 판매로 확정한다(결제 승인). */
    public void confirmSale(Order order) {
        forEachItem(order, Product::confirmSale);
    }

    /** 판매분을 재고로 되돌리고 쿠폰 사용을 복원한다(환불). */
    public void restock(Order order) {
        forEachItem(order, Product::restock);
        restoreCoupon(order);
    }

    private void forEachItem(Order order, BiConsumer<Product, Integer> action) {
        Map<Long, Product> products = lockProducts(order.getItems().stream().map(OrderItem::getProductId).toList());
        order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getProductId))
                .forEach(item -> action.accept(products.get(item.getProductId()), item.getQuantity()));
    }

    private void restoreCoupon(Order order) {
        if (order.getCouponCode() != null) {
            lockCoupon(order.getCouponCode()).restore();
        }
    }
}
