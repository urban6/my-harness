package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.AppProperties;
import com.example.order.common.ErrorCode;
import com.example.order.common.Ids;
import com.example.order.common.Violations;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.order.OrderDtos.CreateOrderRequest;
import com.example.order.order.OrderDtos.OrderItemRequest;
import com.example.order.order.OrderDtos.OrderPage;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final PurchaseOrderRepository orders;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final Reservations reservations;
    private final EntityManager em;
    private final Clock clock;
    private final Duration paymentTtl;

    public OrderService(PurchaseOrderRepository orders, ProductRepository products, CouponRepository coupons,
                        Reservations reservations, EntityManager em, Clock clock, AppProperties props) {
        this.orders = orders;
        this.products = products;
        this.coupons = coupons;
        this.reservations = reservations;
        this.em = em;
        this.clock = clock;
        this.paymentTtl = props.order().paymentTtl();
    }

    /** 헤더·본문 검증(400). 멱등 키 확인보다 먼저 호출한다. */
    public static void validateCreate(String userId, String idempotencyKey, CreateOrderRequest req) {
        Violations v = new Violations();
        v.check(userId != null && !userId.isBlank() && userId.length() <= 50,
                "X-User-Id header must be non-blank and at most 50 characters");
        validateIdempotencyKey(v, idempotencyKey);
        List<OrderItemRequest> items = req.items();
        if (items == null || items.isEmpty() || items.size() > 20) {
            v.check(false, "items must contain 1 to 20 entries");
        } else {
            Set<Long> seen = new HashSet<>();
            for (OrderItemRequest item : items) {
                if (item == null || item.productId() == null) {
                    v.check(false, "each item needs a productId");
                    continue;
                }
                v.check(item.quantity() != null && item.quantity() >= 1 && item.quantity() <= 1000,
                        "quantity must be between 1 and 1000");
                v.check(seen.add(item.productId()), "duplicate productId: " + item.productId());
            }
        }
        v.throwIfAny();
    }

    public static void validateIdempotencyKey(Violations v, String key) {
        v.check(key != null && !key.isEmpty() && key.length() <= 64,
                "Idempotency-Key header must be 1 to 64 characters");
    }

    @Transactional
    public OrderResponse create(String userId, CreateOrderRequest req) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<OrderItemRequest> items = req.items();

        // 잠금 순서: 상품(id 오름차순) → 쿠폰. 존재 확인(404)을 재고·쿠폰 판정(409)보다 먼저 한다.
        Map<Long, Product> locked = new HashMap<>();
        List<Long> sortedIds = items.stream().map(OrderItemRequest::productId).sorted().toList();
        for (Long id : sortedIds) {
            Product p = products.findByIdForUpdate(id).orElseThrow(
                    () -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + id));
            locked.put(id, p);
        }
        Coupon coupon = null;
        if (req.couponCode() != null) {
            coupon = coupons.findByCodeForUpdate(req.couponCode()).orElseThrow(
                    () -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon not found: " + req.couponCode()));
        }

        for (OrderItemRequest item : items) {
            Product p = locked.get(item.productId());
            if (p.available() < item.quantity()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                        "Insufficient stock for product " + p.getId() + ": available " + p.available()
                                + ", requested " + item.quantity());
            }
        }
        long subtotal = 0;
        for (OrderItemRequest item : items) {
            subtotal += locked.get(item.productId()).getPrice() * item.quantity();
        }

        long discount = 0;
        if (coupon != null) {
            boolean usable = coupon.isValidAt(now)
                    && subtotal >= coupon.getMinOrderAmount()
                    && !orders.existsByCouponIdAndUserIdAndStatusIn(coupon.getId(), userId,
                    OrderStatus.COUPON_HOLDING);
            if (!usable) {
                throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                        "Coupon " + coupon.getCode() + " is not applicable to this order");
            }
            if (coupon.isExhausted()) {
                throw new ApiException(ErrorCode.COUPON_EXHAUSTED, "Coupon " + coupon.getCode() + " is exhausted");
            }
            discount = coupon.discountFor(subtotal);
            coupon.use();
        }

        PurchaseOrder order = new PurchaseOrder(userId, coupon == null ? null : coupon.getId(),
                coupon == null ? null : coupon.getCode(), subtotal, discount, now, now.plus(paymentTtl));
        for (OrderItemRequest item : items) {
            Product p = locked.get(item.productId());
            p.reserve(item.quantity());
            order.addItem(p.getId(), item.quantity(), p.getPrice());
        }
        return OrderResponse.from(orders.saveAndFlush(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String rawId) {
        long id = Ids.parse(rawId, ErrorCode.ORDER_NOT_FOUND);
        return orders.findById(id).map(OrderResponse::from).orElseThrow(() -> notFound(rawId));
    }

    @Transactional
    public OrderResponse ship(String rawId) {
        return transition(rawId, OrderStatus.PAID, OrderStatus.SHIPPED, "ship");
    }

    @Transactional
    public OrderResponse deliver(String rawId) {
        return transition(rawId, OrderStatus.SHIPPED, OrderStatus.DELIVERED, "deliver");
    }

    private OrderResponse transition(String rawId, OrderStatus from, OrderStatus to, String action) {
        long id = Ids.parse(rawId, ErrorCode.ORDER_NOT_FOUND);
        PurchaseOrder order = orders.findByIdForUpdate(id).orElseThrow(() -> notFound(rawId));
        // 환불 호출이 진행 중인 PAID 주문은 배송으로 넘기지 않는다.
        if (order.getStatus() != from || order.isGatewayCallInFlight(clock.instant())) {
            throw Reservations.invalidState(order, action);
        }
        order.changeStatus(to);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderPage list(String userId, String status, String size, String cursor) {
        OrderStatus statusFilter = null;
        if (status != null) {
            try {
                statusFilter = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw Violations.error("Unknown status: " + status);
            }
        }
        int pageSize = DEFAULT_PAGE_SIZE;
        if (size != null) {
            try {
                pageSize = Integer.parseInt(size);
            } catch (NumberFormatException e) {
                throw Violations.error("size must be an integer");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw Violations.error("size must be between 1 and 100");
            }
        }
        Cursor after = cursor == null ? null : Cursor.decode(cursor);

        StringBuilder jpql = new StringBuilder("select o from PurchaseOrder o where 1 = 1");
        if (userId != null && !userId.isEmpty()) {
            jpql.append(" and o.userId = :userId");
        }
        if (statusFilter != null) {
            jpql.append(" and o.status = :status");
        }
        if (after != null) {
            jpql.append(" and (o.createdAt < :at or (o.createdAt = :at and o.id < :id))");
        }
        jpql.append(" order by o.createdAt desc, o.id desc");
        TypedQuery<PurchaseOrder> query = em.createQuery(jpql.toString(), PurchaseOrder.class);
        if (userId != null && !userId.isEmpty()) {
            query.setParameter("userId", userId);
        }
        if (statusFilter != null) {
            query.setParameter("status", statusFilter);
        }
        if (after != null) {
            query.setParameter("at", after.createdAt());
            query.setParameter("id", after.id());
        }
        query.setMaxResults(pageSize + 1);

        List<PurchaseOrder> found = new ArrayList<>(query.getResultList());
        String nextCursor = null;
        if (found.size() > pageSize) {
            found = found.subList(0, pageSize);
            PurchaseOrder last = found.get(found.size() - 1);
            nextCursor = new Cursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new OrderPage(found.stream().map(OrderResponse::from).toList(), nextCursor);
    }

    static ApiException notFound(String id) {
        return new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order not found: " + id);
    }

    /** (createdAt, id) 키셋 커서. 불투명 문자열로 노출한다. */
    private record Cursor(Instant createdAt, long id) {

        String encode() {
            long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String raw) {
            try {
                String text = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
                int sep = text.indexOf(':');
                if (sep < 0) {
                    throw new IllegalArgumentException("no separator");
                }
                long micros = Long.parseLong(text.substring(0, sep));
                long id = Long.parseLong(text.substring(sep + 1));
                return new Cursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
            } catch (RuntimeException e) {
                throw Violations.error("Invalid cursor");
            }
        }
    }
}
