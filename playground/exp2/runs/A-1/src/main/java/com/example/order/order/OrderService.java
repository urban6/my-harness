package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.order.OrderDtos.CreateOrderRequest;
import com.example.order.order.OrderDtos.OrderItemRequest;
import com.example.order.order.OrderDtos.OrderPage;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;
    private final OrderInventory inventory;
    private final PaymentGatewayClient paymentGateway;
    private final OrderProperties properties;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
                        CouponRepository couponRepository, OrderInventory inventory,
                        PaymentGatewayClient paymentGateway, OrderProperties properties,
                        EntityManager em, TransactionTemplate tx, Clock clock) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
        this.inventory = inventory;
        this.paymentGateway = paymentGateway;
        this.properties = properties;
        this.em = em;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * 주문 생성. 검사 순서는 C3를 따른다: 404(상품 → 쿠폰) → 409(재고 → 쿠폰).
     * 상품은 id 오름차순으로 잠근 뒤 쿠폰을 잠그므로 동시 요청 사이에 교착이 생기지 않는다.
     */
    @Transactional
    public OrderResponse create(String userId, CreateOrderRequest request) {
        Instant now = Times.now(clock);

        Map<Long, Product> products = new HashMap<>();
        request.items().stream()
                .map(OrderItemRequest::productId)
                .sorted()
                .forEach(id -> products.put(id, productRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + id + " not found"))));

        Coupon coupon = null;
        if (request.couponCode() != null) {
            coupon = couponRepository.findByCodeForUpdate(request.couponCode())
                    .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND,
                            "Coupon " + request.couponCode() + " not found"));
        }

        long subtotal = 0;
        for (OrderItemRequest item : request.items()) {
            Product product = products.get(item.productId());
            if (product.available() < item.quantity()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Product " + product.getId()
                        + " has only " + product.available() + " available, requested " + item.quantity());
            }
            subtotal = Math.addExact(subtotal, Math.multiplyExact(product.getPrice(), (long) item.quantity()));
        }

        long discount = 0;
        if (coupon != null) {
            checkCouponApplicable(coupon, userId, subtotal, now);
            discount = coupon.discountFor(subtotal);
            coupon.use();
        }

        Order order = new Order(userId, request.couponCode(), subtotal, discount, now, now.plus(properties.paymentTtl()));
        for (OrderItemRequest item : request.items()) {
            Product product = products.get(item.productId());
            product.reserve(item.quantity());
            order.addItem(product.getId(), item.quantity(), product.getPrice());
        }
        orderRepository.save(order);
        return OrderResponse.from(order);
    }

    private void checkCouponApplicable(Coupon coupon, String userId, long subtotal, Instant now) {
        if (!coupon.isValidAt(now)) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE, "Coupon " + coupon.getCode() + " is not valid now");
        }
        if (subtotal < coupon.getMinOrderAmount()) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "Order amount " + subtotal + " is below minimum " + coupon.getMinOrderAmount());
        }
        if (orderRepository.existsByUserAndCouponInStatuses(userId, coupon.getCode(), OrderStatus.USING_COUPON)) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "Coupon " + coupon.getCode() + " is already in use by this user");
        }
        if (coupon.isExhausted()) {
            throw new ApiException(ErrorCode.COUPON_EXHAUSTED, "Coupon " + coupon.getCode() + " is exhausted");
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse get(long id) {
        return OrderResponse.from(orderRepository.findById(id).orElseThrow(() -> notFound(id)));
    }

    /**
     * 취소·환불. PAID 주문은 PG 환불이 성공한 경우에만 반영되고, PG 장애면 트랜잭션이 롤백되어 아무것도 바뀌지 않는다.
     */
    public OrderResponse cancel(long id) {
        return tx.execute(status -> {
            Order order = lock(id);
            switch (order.getStatus()) {
                case PENDING_PAYMENT -> {
                    order.markCancelled();
                    inventory.releaseReservation(order);
                }
                case PAID -> {
                    if (order.getPaymentId() != null) {
                        paymentGateway.refund(order.getPaymentId());
                    }
                    order.markRefunded();
                    inventory.restockAndRestoreCoupon(order);
                }
                default -> throw invalidState(order, "cancel");
            }
            return OrderResponse.from(order);
        });
    }

    @Transactional
    public OrderResponse ship(long id) {
        Order order = lock(id);
        if (order.getStatus() != OrderStatus.PAID) {
            throw invalidState(order, "ship");
        }
        order.markShipped();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse deliver(long id) {
        Order order = lock(id);
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw invalidState(order, "deliver");
        }
        order.markDelivered();
        return OrderResponse.from(order);
    }

    /** 목록: createdAt 내림차순, 같으면 id 내림차순 키셋 페이지네이션. */
    @Transactional(readOnly = true)
    public OrderPage list(String userId, OrderStatus status, int size, OrderCursor cursor) {
        StringBuilder jpql = new StringBuilder("select o from Order o where 1 = 1");
        if (userId != null) {
            jpql.append(" and o.userId = :userId");
        }
        if (status != null) {
            jpql.append(" and o.status = :status");
        }
        if (cursor != null) {
            jpql.append(" and (o.createdAt < :cursorCreatedAt or (o.createdAt = :cursorCreatedAt and o.id < :cursorId))");
        }
        jpql.append(" order by o.createdAt desc, o.id desc");

        TypedQuery<Order> query = em.createQuery(jpql.toString(), Order.class);
        if (userId != null) {
            query.setParameter("userId", userId);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (cursor != null) {
            query.setParameter("cursorCreatedAt", cursor.createdAt());
            query.setParameter("cursorId", cursor.id());
        }
        List<Order> rows = query.setMaxResults(size + 1).getResultList();

        boolean hasNext = rows.size() > size;
        List<Order> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext ? OrderCursor.of(page.get(page.size() - 1)).encode() : null;
        return new OrderPage(page.stream().map(OrderResponse::from).toList(), nextCursor);
    }

    /** 만료 대상 주문 하나를 EXPIRED로 바꾼다. 결제 중이라 잠겨 있으면 건너뛴다. */
    public boolean expireIfDue(long id) {
        Boolean expired = tx.execute(status -> {
            Instant now = Times.now(clock);
            List<?> locked = em.createNativeQuery("""
                            select id from orders
                            where id = :id and status = 'PENDING_PAYMENT' and expires_at <= :now
                            for update skip locked
                            """)
                    .setParameter("id", id)
                    .setParameter("now", now)
                    .getResultList();
            if (locked.isEmpty()) {
                return false;
            }
            Order order = em.find(Order.class, id);
            order.markExpired();
            inventory.releaseReservation(order);
            return true;
        });
        return Boolean.TRUE.equals(expired);
    }

    public List<Long> findExpiredPendingIds(int limit) {
        return orderRepository.findExpiredPendingIds(Times.now(clock), PageRequest.of(0, limit));
    }

    Order lock(long id) {
        return orderRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
    }

    static ApiException notFound(long id) {
        return new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order " + id + " not found");
    }

    static ApiException invalidState(Order order, String action) {
        return new ApiException(ErrorCode.INVALID_STATE,
                "Cannot " + action + " order " + order.getId() + " in status " + order.getStatus());
    }
}
