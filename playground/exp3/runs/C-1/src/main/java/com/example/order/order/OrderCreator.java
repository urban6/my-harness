package com.example.order.order;

import com.example.order.common.Problems;
import com.example.order.common.Times;
import com.example.order.config.OrderPaymentProperties;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.coupon.CouponUsageRepository;
import com.example.order.coupon.DiscountCalculator;
import com.example.order.product.StockRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transaction: order claim INSERT -> coupon rule pre-check -> stock reservation (id asc) -> coupon claim
 * -> items. Any failure rolls everything back (the idempotency key is then not consumed).
 */
@Service
public class OrderCreator {

    private final OrderRepository orders;
    private final StockRepository stock;
    private final CouponRepository coupons;
    private final CouponUsageRepository couponUsage;
    private final OrderPaymentProperties props;
    private final Clock clock;

    public OrderCreator(OrderRepository orders, StockRepository stock, CouponRepository coupons,
                        CouponUsageRepository couponUsage, OrderPaymentProperties props, Clock clock) {
        this.orders = orders;
        this.stock = stock;
        this.coupons = coupons;
        this.couponUsage = couponUsage;
        this.props = props;
        this.clock = clock;
    }

    /** @return the new order id, or empty when the (user, key) pair already has an order (caller replays). */
    @Transactional
    public Optional<Long> create(String userId, String idempotencyKey, String requestHash, CreateOrderRequest request) {
        Instant now = Times.now(clock);
        List<CreateOrderRequest.Item> requested = request.items();

        Map<Long, Long> prices = stock.findPrices(requested.stream().map(CreateOrderRequest.Item::productId).toList());
        long subtotal = 0;
        List<OrderItemRow> lines = new ArrayList<>();
        int lineNo = 1;
        for (CreateOrderRequest.Item item : requested) {
            Long price = prices.get(item.productId());
            if (price == null) {
                throw Problems.productNotFound(item.productId());
            }
            subtotal += price * item.quantity();
            lines.add(new OrderItemRow(0, lineNo++, item.productId(), item.quantity(), price));
        }

        Coupon coupon = null;
        if (request.couponCode() != null) {
            coupon = coupons.findByCode(request.couponCode())
                    .orElseThrow(() -> Problems.couponNotFound(request.couponCode()));
        }

        Optional<Long> inserted = orders.insertIfAbsent(userId, coupon == null ? null : coupon.getId(),
                coupon == null ? null : coupon.getCode(), subtotal, idempotencyKey, requestHash, now,
                now.plus(props.ttl()));
        if (inserted.isEmpty()) {
            return Optional.empty();
        }
        long orderId = inserted.get();

        if (coupon != null) {
            precheckCoupon(coupon, subtotal, now);
        }

        lines.stream().sorted(Comparator.comparingLong(OrderItemRow::productId)).forEach(line -> {
            if (!stock.reserve(line.productId(), line.quantity(), now)) {
                throw Problems.insufficientStock(line.productId(), line.quantity(), stock.available(line.productId()));
            }
        });

        if (coupon != null) {
            if (!couponUsage.tryUse(coupon.getId(), now)) {
                boolean inPeriod = !now.isBefore(coupon.getValidFrom()) && !now.isAfter(coupon.getValidUntil());
                throw inPeriod ? Problems.couponExhausted(coupon.getCode())
                        : Problems.couponNotInPeriod(coupon.getCode());
            }
            long discount = DiscountCalculator.calculate(coupon, subtotal);
            orders.applyDiscount(orderId, discount, subtotal - discount, now);
        }

        orders.insertItems(orderId, lines);
        return Optional.of(orderId);
    }

    private static void precheckCoupon(Coupon coupon, long subtotal, Instant now) {
        if (now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidUntil())) {
            throw Problems.couponNotInPeriod(coupon.getCode());
        }
        if (subtotal < coupon.getMinOrderAmount()) {
            throw Problems.couponMinOrderNotMet(coupon.getCode(), coupon.getMinOrderAmount(), subtotal);
        }
        if (coupon.getUsedCount() >= coupon.getTotalQuantity()) {
            throw Problems.couponExhausted(coupon.getCode());
        }
    }
}
