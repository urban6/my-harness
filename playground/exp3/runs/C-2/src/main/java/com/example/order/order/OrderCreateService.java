package com.example.order.order;

import com.example.order.common.Hashing;
import com.example.order.common.Problems;
import com.example.order.common.TimeSupport;
import com.example.order.config.OrderProperties;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.idempotency.IdempotencyKey;
import com.example.order.idempotency.IdempotencyKeyRepository;
import com.example.order.order.OrderDtos.CreateOrderRequest;
import com.example.order.order.OrderDtos.ItemRequest;
import com.example.order.order.OrderDtos.OrderResponse;
import com.example.order.order.OrderDtos.OrderResult;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** POST /api/orders (01 section 2.5). One transaction; any failure rolls back reservation and idempotency key. */
@Service
public class OrderCreateService {

    private static final String OUTCOME_CREATED = "CREATED";

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;
    private final PurchaseOrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final IdempotencyKeyRepository idempotencyRepository;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderCreateService(ProductRepository productRepository, CouponRepository couponRepository,
            PurchaseOrderRepository orderRepository, OrderItemRepository itemRepository,
            IdempotencyKeyRepository idempotencyRepository, OrderProperties properties, Clock clock) {
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public OrderResult create(String userId, String idempotencyKey, CreateOrderRequest request) {
        Instant now = TimeSupport.now(clock);
        String hash = Hashing.sha256Hex(canonical(request));

        // 3. claim the key (waits for a concurrent in-flight claim of the same key)
        if (idempotencyRepository.claim(IdempotencyKey.CREATE_ORDER, userId, idempotencyKey, hash, now) == 0) {
            return replay(userId, idempotencyKey, hash);
        }

        // 4. products (ascending id); nothing has been changed yet
        List<ItemRequest> sortedItems = request.items().stream()
                .sorted(Comparator.comparing(ItemRequest::productId)).toList();
        Map<Long, Product> products = productRepository
                .findAllById(sortedItems.stream().map(ItemRequest::productId).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (ItemRequest item : sortedItems) {
            if (!products.containsKey(item.productId())) {
                throw Problems.productNotFound(item.productId());
            }
        }

        // snapshot items in request order, compute subtotal
        List<OrderItem> orderItems = new ArrayList<>();
        long subtotal = 0;
        for (ItemRequest item : request.items()) {
            long unitPrice = products.get(item.productId()).getPrice();
            int quantity = item.quantity().intValue();
            subtotal = Math.addExact(subtotal, Math.multiplyExact(unitPrice, (long) quantity));
            orderItems.add(new OrderItem(null, item.productId(), quantity, unitPrice));
        }

        // 5. coupon pre-check
        String couponCode = request.couponCode();
        Coupon coupon = null;
        if (couponCode != null) {
            coupon = couponRepository.findByCode(couponCode).orElseThrow(() -> Problems.couponNotFound(couponCode));
            checkApplicable(coupon, subtotal, now);
        }

        // 6. reserve stock (ascending product id)
        for (ItemRequest item : sortedItems) {
            int quantity = item.quantity().intValue();
            if (productRepository.reserve(item.productId(), quantity) != 1) {
                Integer available = productRepository.findAvailable(item.productId());
                throw Problems.insufficientStock(item.productId(), quantity, available == null ? 0 : available);
            }
        }

        // 7. use coupon (after products)
        long discount = 0;
        if (coupon != null) {
            if (couponRepository.use(couponCode, now) != 1) {
                throw Problems.couponNotApplicable(couponCode, "EXHAUSTED");
            }
            discount = coupon.discountFor(subtotal);
        }

        // 8. persist order + items, finish the idempotency record
        PurchaseOrder order = orderRepository.save(new PurchaseOrder(userId, couponCode, subtotal, discount, now,
                now.plus(properties.paymentTtl())));
        List<OrderItem> savedItems = itemRepository.saveAll(orderItems.stream()
                .map(i -> new OrderItem(order.getId(), i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList());
        idempotencyRepository.complete(IdempotencyKey.CREATE_ORDER, userId, idempotencyKey, order.getId(),
                OUTCOME_CREATED);
        return new OrderResult(OrderResponse.of(order, savedItems), false);
    }

    private OrderResult replay(String userId, String idempotencyKey, String hash) {
        IdempotencyKey existing = idempotencyRepository
                .findByOperationAndScopeKeyAndIdemKey(IdempotencyKey.CREATE_ORDER, userId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished after conflict"));
        if (!existing.getRequestHash().equals(hash)) {
            throw Problems.idempotencyKeyReused(idempotencyKey);
        }
        if (existing.getOrderId() == null) {
            throw new IllegalStateException("Idempotency key has no order");
        }
        PurchaseOrder order = orderRepository.findById(existing.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order of idempotency key missing"));
        return new OrderResult(OrderResponse.of(order, itemRepository.findByOrderIdOrderByIdAsc(order.getId())), true);
    }

    private static void checkApplicable(Coupon coupon, long subtotal, Instant now) {
        String code = coupon.getCode();
        if (now.isBefore(coupon.getValidFrom())) {
            throw Problems.couponNotApplicable(code, "NOT_YET_VALID");
        }
        if (!now.isBefore(coupon.getValidUntil())) {
            throw Problems.couponNotApplicable(code, "EXPIRED");
        }
        if (subtotal < coupon.getMinOrderAmount()) {
            throw Problems.couponNotApplicable(code, "BELOW_MIN_ORDER_AMOUNT");
        }
        if (coupon.getUsedCount() >= coupon.getTotalQuantity()) {
            throw Problems.couponNotApplicable(code, "EXHAUSTED");
        }
    }

    /** items sorted by productId as "productId:quantity", then "|" and the coupon code (or empty). */
    static String canonical(CreateOrderRequest request) {
        String items = request.items().stream()
                .sorted(Comparator.comparing(ItemRequest::productId))
                .map(i -> i.productId() + ":" + i.quantity())
                .collect(Collectors.joining(","));
        return items + "|" + (request.couponCode() == null ? "" : request.couponCode());
    }
}
