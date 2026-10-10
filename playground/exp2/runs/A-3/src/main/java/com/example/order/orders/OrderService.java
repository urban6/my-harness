package com.example.order.orders;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.orders.OrderDtos.CreateOrderRequest;
import com.example.order.orders.OrderDtos.ItemRequest;
import com.example.order.orders.OrderDtos.OrderResponse;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PaymentResult;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.order.common.Validations.inRange;
import static com.example.order.common.Validations.notNull;
import static com.example.order.common.Validations.require;

/**
 * 주문 상태 전이와 재고·쿠폰 정합성.
 * <p>
 * 잠금 순서는 항상 주문 → 상품(id 오름차순) → 쿠폰 이다. 주문 생성은 주문 행을 잠그지 않으므로
 * 상품 → 쿠폰 순서만 지키면 교착 상태가 생기지 않는다.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final PaymentGatewayClient gateway;
    private final TransactionTemplate tx;
    private final Duration paymentTtl;

    public OrderService(OrderRepository orders, ProductRepository products, CouponRepository coupons,
                        PaymentGatewayClient gateway, PlatformTransactionManager transactionManager,
                        OrderPaymentProperties paymentProperties) {
        this.orders = orders;
        this.products = products;
        this.coupons = coupons;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(transactionManager);
        this.paymentTtl = paymentProperties.ttl();
    }

    /** R3.2 */
    public static void validateCreate(CreateOrderRequest request) {
        notNull(request, "body");
        List<ItemRequest> items = notNull(request.items(), "items");
        require(!items.isEmpty() && items.size() <= 20, "items must contain 1 to 20 entries");
        Set<Long> seen = new HashSet<>();
        for (ItemRequest item : items) {
            notNull(item, "items[]");
            notNull(item.productId(), "items[].productId");
            inRange(item.quantity(), 1, 1_000, "items[].quantity");
            require(seen.add(item.productId()), "items must not contain duplicate productId " + item.productId());
        }
        if (request.couponCode() != null) {
            require(!request.couponCode().isBlank(), "couponCode must not be blank");
        }
    }

    @Transactional
    public OrderResponse create(String userId, CreateOrderRequest request) {
        Instant now = now();
        List<Long> productIds = request.items().stream().map(ItemRequest::productId).sorted().toList();
        Map<Long, Product> productById = products.findAllForUpdate(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (ItemRequest item : request.items()) {
            if (!productById.containsKey(item.productId())) {
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + item.productId() + " not found");
            }
        }
        Coupon coupon = null;
        if (request.couponCode() != null) {
            coupon = coupons.findByCodeForUpdate(request.couponCode())
                    .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND,
                            "Coupon " + request.couponCode() + " not found"));
        }
        for (ItemRequest item : request.items()) {
            Product product = productById.get(item.productId());
            if (product.available() < item.quantity()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                        "Product " + product.getId() + " has only " + product.available() + " available");
            }
        }

        Order order = new Order(userId, now, now.plus(paymentTtl));
        for (ItemRequest item : request.items()) {
            order.addItem(item.productId(), item.quantity(), productById.get(item.productId()).getPrice());
        }
        if (coupon != null) {
            checkCouponApplicable(coupon, userId, order.getSubtotal(), now);
            coupon.use();
            order.applyCoupon(coupon.getCode(), coupon.discountFor(order.getSubtotal()));
        }
        for (ItemRequest item : request.items()) {
            productById.get(item.productId()).reserve(item.quantity());
        }
        orders.save(order);
        return OrderResponse.from(order);
    }

    /** R2.5 */
    private void checkCouponApplicable(Coupon coupon, String userId, long subtotal, Instant now) {
        if (!coupon.isValidAt(now)) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE, "Coupon " + coupon.getCode() + " is not valid now");
        }
        if (subtotal < coupon.getMinOrderAmount()) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "Order subtotal is below the coupon minimum of " + coupon.getMinOrderAmount());
        }
        if (orders.existsByUserAndCouponInStatuses(userId, coupon.getCode(), OrderStatus.USING_COUPON)) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "Coupon " + coupon.getCode() + " is already in use by this user");
        }
        if (coupon.isExhausted()) {
            throw new ApiException(ErrorCode.COUPON_EXHAUSTED, "Coupon " + coupon.getCode() + " is exhausted");
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse get(long id) {
        return orders.findById(id).map(OrderResponse::from).orElseThrow(() -> notFound(id));
    }

    /**
     * R5. 결제 요청이 동시에 와도 PG 호출이 한 번만 일어나도록 주문 행을 잠근 채로 PG 를 호출한다.
     * PG 장애면 트랜잭션을 롤백해 주문·재고·쿠폰을 그대로 둔다.
     */
    public OrderResponse pay(long orderId, String idempotencyKey, String cardToken) {
        return tx.execute(status -> {
            Order order = lock(orderId);
            if (expireIfOverdue(order)) {
                return Outcome.failure(ErrorCode.INVALID_STATE, "Payment window for order " + orderId + " has expired");
            }
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                return invalidState(order, "pay");
            }
            if (order.getTotalPrice() == 0) {
                completePayment(order, null);
                return Outcome.success(order);
            }
            PaymentResult result = gateway.pay(idempotencyKey, orderId, order.getTotalPrice(), cardToken);
            if (result.approved()) {
                completePayment(order, result.paymentId());
                return Outcome.success(order);
            }
            order.markPaymentFailed();
            releaseReservation(order);
            restoreCoupon(order);
            return Outcome.failure(ErrorCode.PAYMENT_DECLINED, "Payment for order " + orderId + " was declined");
        }).orThrow();
    }

    /** R7 */
    public OrderResponse cancel(long orderId) {
        return tx.execute(status -> {
            Order order = lock(orderId);
            if (expireIfOverdue(order)) {
                return Outcome.failure(ErrorCode.INVALID_STATE, "Order " + orderId + " has expired");
            }
            switch (order.getStatus()) {
                case PENDING_PAYMENT -> {
                    order.markCancelled();
                    releaseReservation(order);
                    restoreCoupon(order);
                }
                case PAID -> {
                    if (order.getPaymentId() != null) {
                        gateway.refund(order.getPaymentId());
                    }
                    order.markRefunded();
                    for (Map.Entry<Product, Integer> e : lockProducts(order).entrySet()) {
                        e.getKey().restock(e.getValue());
                    }
                    restoreCoupon(order);
                }
                default -> {
                    return invalidState(order, "cancel");
                }
            }
            return Outcome.success(order);
        }).orThrow();
    }

    /** R8 */
    @Transactional
    public OrderResponse ship(long orderId) {
        Order order = lock(orderId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw invalidState(order, "ship").error();
        }
        order.markShipped();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse deliver(long orderId) {
        Order order = lock(orderId);
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw invalidState(order, "deliver").error();
        }
        order.markDelivered();
        return OrderResponse.from(order);
    }

    /** R6. 결제·취소 처리 중인 주문은 건너뛰고 다음 주기에 다시 본다. */
    @Transactional
    public boolean expireIfDue(long orderId) {
        return orders.findByIdForUpdateSkipLocked(orderId).map(this::expireIfOverdue).orElse(false);
    }

    private boolean expireIfOverdue(Order order) {
        if (!order.isPaymentOverdue(now())) {
            return false;
        }
        order.markExpired();
        releaseReservation(order);
        restoreCoupon(order);
        return true;
    }

    private void completePayment(Order order, String paymentId) {
        for (Map.Entry<Product, Integer> e : lockProducts(order).entrySet()) {
            e.getKey().confirmSale(e.getValue());
        }
        order.markPaid(paymentId, now());
    }

    private void releaseReservation(Order order) {
        for (Map.Entry<Product, Integer> e : lockProducts(order).entrySet()) {
            e.getKey().releaseReservation(e.getValue());
        }
    }

    private void restoreCoupon(Order order) {
        if (order.getCouponCode() != null) {
            coupons.findByCodeForUpdate(order.getCouponCode()).orElseThrow().restore();
        }
    }

    /** 주문의 상품들을 id 오름차순으로 잠그고 상품별 주문 수량을 돌려준다. */
    private Map<Product, Integer> lockProducts(Order order) {
        Map<Long, Integer> quantities = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
        List<Long> ids = quantities.keySet().stream().sorted().toList();
        return products.findAllForUpdate(ids).stream()
                .collect(Collectors.toMap(Function.identity(), p -> quantities.get(p.getId()),
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private Order lock(long orderId) {
        return orders.findByIdForUpdate(orderId).orElseThrow(() -> notFound(orderId));
    }

    private static ApiException notFound(long orderId) {
        return new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order " + orderId + " not found");
    }

    private static Outcome invalidState(Order order, String action) {
        return Outcome.failure(ErrorCode.INVALID_STATE,
                "Cannot " + action + " order " + order.getId() + " in status " + order.getStatus());
    }

    static Instant now() {
        // PostgreSQL timestamptz 정밀도(마이크로초)에 맞춰 응답과 저장 값을 일치시킨다
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 트랜잭션을 커밋한 뒤에 오류로 응답해야 하는 경우(거절·만료)를 위한 결과. */
    private record Outcome(OrderResponse response, ErrorCode code, String detail) {

        static Outcome success(Order order) {
            return new Outcome(OrderResponse.from(order), null, null);
        }

        static Outcome failure(ErrorCode code, String detail) {
            return new Outcome(null, code, detail);
        }

        ApiException error() {
            return new ApiException(code, detail);
        }

        OrderResponse orThrow() {
            if (code != null) {
                throw error();
            }
            return response;
        }
    }
}
