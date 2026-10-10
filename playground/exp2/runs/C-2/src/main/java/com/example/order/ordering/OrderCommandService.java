package com.example.order.ordering;

import com.example.order.common.config.OrderProperties;
import com.example.order.common.error.CouponExhaustedException;
import com.example.order.common.error.CouponNotApplicableException;
import com.example.order.common.error.CouponNotFoundException;
import com.example.order.common.error.InsufficientStockException;
import com.example.order.common.error.InvalidOrderStateException;
import com.example.order.common.error.OrderNotFoundException;
import com.example.order.common.error.PaymentDeclinedException;
import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponRepository;
import com.example.order.idempotency.IdempotencyScope;
import com.example.order.idempotency.IdempotencyService;
import com.example.order.idempotency.IdempotencyService.Begin;
import com.example.order.idempotency.IdempotentResponse;
import com.example.order.idempotency.RequestFingerprint;
import com.example.order.ordering.dto.CreateOrderRequest;
import com.example.order.ordering.dto.OrderResponse;
import com.example.order.ordering.dto.PayRequest;
import com.example.order.payment.PaymentGatewayClient;
import com.example.order.payment.PaymentResult;
import com.example.order.product.Product;
import com.example.order.product.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 멱등 키 / PG 호출 / 커밋 후 오류 응답(402)이 얽힌 쓰기 흐름. 트랜잭션 경계를 TransactionTemplate으로 명시한다.
 * 이 클래스 자체에는 트랜잭션이 없고, 멱등 begin/abandon은 각자 짧게 커밋된다 (01 설계 6.4, 10.3).
 */
@Service
public class OrderCommandService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;
    private final OrderEffects effects;
    private final IdempotencyService idempotency;
    private final PaymentGatewayClient gateway;
    private final OrderProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public OrderCommandService(OrderRepository orderRepository, ProductRepository productRepository,
            CouponRepository couponRepository, OrderEffects effects, IdempotencyService idempotency,
            PaymentGatewayClient gateway, OrderProperties properties, Clock clock, ObjectMapper objectMapper,
            TransactionTemplate tx) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.couponRepository = couponRepository;
        this.effects = effects;
        this.idempotency = idempotency;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    // =====================================================================
    // E5. 주문 생성
    // =====================================================================

    public IdempotentResponse create(String userId, String idempotencyKey, CreateOrderRequest request) {
        String fingerprint = RequestFingerprint.of(IdempotencyScope.ORDER_CREATE, "/api/orders", userId, json(request));
        Begin begin = idempotency.begin(IdempotencyScope.ORDER_CREATE, idempotencyKey, fingerprint);
        if (begin.replay() != null) {
            return begin.replay();
        }
        long recordId = begin.recordId();
        try {
            return tx.execute(status -> doCreate(recordId, userId, request));
        } catch (RuntimeException e) {
            idempotency.abandon(recordId);
            throw e;
        }
    }

    private IdempotentResponse doCreate(long recordId, String userId, CreateOrderRequest request) {
        Instant now = now();

        // 404: 상품(id 오름차순 락) -> 쿠폰(락)
        Map<Long, Product> products = effects.lockProducts(request.items().stream().map(CreateOrderRequest.Item::productId).toList());
        Coupon coupon = null;
        if (request.couponCode() != null) {
            if (request.couponCode().isBlank()) {
                throw new CouponNotFoundException("쿠폰 " + request.couponCode() + "을(를) 찾을 수 없습니다.");
            }
            coupon = couponRepository.findByCodeForUpdate(request.couponCode())
                    .orElseThrow(() -> new CouponNotFoundException("쿠폰 " + request.couponCode() + "을(를) 찾을 수 없습니다."));
        }

        // 409 재고
        for (CreateOrderRequest.Item item : request.items()) {
            Product p = products.get(item.productId());
            if (p.available() < item.quantity()) {
                throw new InsufficientStockException("상품 " + p.getId() + "의 주문 가능 수량(" + p.available()
                        + ")이 요청 수량(" + item.quantity() + ")보다 적습니다.");
            }
        }

        long subtotal = 0;
        List<OrderLine> lines = new ArrayList<>();
        for (CreateOrderRequest.Item item : request.items()) {
            Product p = products.get(item.productId());
            lines.add(new OrderLine(p.getId(), item.quantity(), p.getPrice()));
            subtotal = Math.addExact(subtotal, Math.multiplyExact(p.getPrice(), (long) item.quantity()));
        }

        // 409 쿠폰: 적용 불가(a 유효기간, b 최소금액, c 사용자 중복) -> 소진(d)
        long discount = 0;
        if (coupon != null) {
            if (!coupon.isValidAt(now)) {
                throw new CouponNotApplicableException("쿠폰 " + coupon.getCode() + "의 유효기간이 아닙니다.");
            }
            if (subtotal < coupon.getMinOrderAmount()) {
                throw new CouponNotApplicableException("주문 금액이 쿠폰 최소 주문 금액(" + coupon.getMinOrderAmount() + ")보다 적습니다.");
            }
            if (orderRepository.existsActiveCouponUse(coupon.getCode(), userId)) {
                throw new CouponNotApplicableException("이미 이 쿠폰을 사용 중인 주문이 있습니다.");
            }
            if (coupon.isExhausted()) {
                throw new CouponExhaustedException("쿠폰 " + coupon.getCode() + "이(가) 모두 소진되었습니다.");
            }
            discount = coupon.calculateDiscount(subtotal);
        }

        // 반영 (한 트랜잭션: 부분 반영 없음)
        for (CreateOrderRequest.Item item : request.items()) {
            products.get(item.productId()).reserve(item.quantity());
        }
        if (coupon != null) {
            coupon.use();
        }
        Order order = new Order(userId, coupon == null ? null : coupon.getCode(), lines, subtotal, discount,
                now, now.plus(properties.paymentTtl()));
        orderRepository.saveAndFlush(order);

        IdempotentResponse response = new IdempotentResponse(HttpStatus.CREATED.value(),
                json(OrderResponse.from(order)), "/api/orders/" + order.getId());
        idempotency.complete(recordId, response);
        return response;
    }

    // =====================================================================
    // E8. 결제
    // =====================================================================

    private record PayOutcome(boolean declined, IdempotentResponse response) {
    }

    public IdempotentResponse pay(long orderId, String idempotencyKey, String userIdHeader, PayRequest request) {
        String fingerprint = RequestFingerprint.of(IdempotencyScope.ORDER_PAY, "/api/orders/" + orderId + "/pay",
                userIdHeader, json(request));
        Begin begin = idempotency.begin(IdempotencyScope.ORDER_PAY, idempotencyKey, fingerprint);
        if (begin.replay() != null) {
            return begin.replay();
        }
        long recordId = begin.recordId();
        PayOutcome outcome;
        try {
            outcome = tx.execute(status -> doPay(recordId, orderId, idempotencyKey, request));
        } catch (RuntimeException e) {
            idempotency.abandon(recordId);
            throw e;
        }
        if (outcome.declined()) {
            // 상태 변경은 커밋되었다. 비2xx이므로 키는 저장하지 않는다 (R4.4).
            idempotency.abandon(recordId);
            throw new PaymentDeclinedException("결제가 거절되었습니다.");
        }
        return outcome.response();
    }

    private PayOutcome doPay(long recordId, long orderId, String idempotencyKey, PayRequest request) {
        // 이 트랜잭션에서 주문에 대한 첫 접근이 FOR UPDATE 이어야 한다.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("주문 " + orderId + "을(를) 찾을 수 없습니다."));
        Instant now = now();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.isExpiredAt(now)) {
            throw new InvalidOrderStateException("주문 " + orderId + "은(는) 결제할 수 없는 상태입니다: " + order.getStatus());
        }

        boolean approved = true;
        String paymentId = null;
        if (order.getTotalPrice() > 0) { // R5.7: 0원이면 PG 없이 바로 승인
            // 주문 행 락을 쥔 채 호출한다 (R10.5). 장애면 예외 -> 롤백 (주문/재고/쿠폰 불변).
            PaymentResult result = gateway.pay(idempotencyKey, orderId, order.getTotalPrice(), request.cardToken());
            approved = result.approved();
            paymentId = result.paymentId();
        }

        if (approved) {
            effects.confirmSale(order);
            order.markPaid(now(), paymentId);
            IdempotentResponse response = new IdempotentResponse(HttpStatus.OK.value(), json(OrderResponse.from(order)), null);
            idempotency.complete(recordId, response);
            return new PayOutcome(false, response);
        }
        effects.releaseReservation(order);
        effects.releaseCoupon(order);
        order.markPaymentFailed(now(), paymentId);
        return new PayOutcome(true, null);
    }

    // =====================================================================
    // E9. 취소 / 환불
    // =====================================================================

    public OrderResponse cancel(long orderId) {
        return tx.execute(status -> {
            Order order = orderRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new OrderNotFoundException("주문 " + orderId + "을(를) 찾을 수 없습니다."));
            Instant now = now();
            switch (order.getStatus()) {
                case PENDING_PAYMENT -> {
                    if (order.isExpiredAt(now)) { // 사실상 EXPIRED: 스케줄러가 곧 전이시킨다
                        throw new InvalidOrderStateException("주문 " + orderId + "은(는) 결제 대기 시간이 지났습니다.");
                    }
                    effects.releaseReservation(order);
                    effects.releaseCoupon(order);
                    order.cancel(now);
                }
                case PAID -> {
                    if (order.getPaymentId() != null) {
                        gateway.refund(order.getPaymentId()); // 장애면 예외 -> 롤백
                    }
                    effects.restock(order);
                    effects.releaseCoupon(order);
                    order.refund(now);
                }
                default -> throw new InvalidOrderStateException(
                        "주문 " + orderId + "의 현재 상태(" + order.getStatus() + ")에서는 취소할 수 없습니다.");
            }
            return OrderResponse.from(order);
        });
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }
}
