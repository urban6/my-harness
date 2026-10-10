package com.example.order;

import com.example.order.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.order.IdempotencyTest.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R10. 동시성")
class ConcurrencyTest extends IntegrationTest {

    private static long count(List<Resp> responses, int status) {
        return responses.stream().filter(r -> r.status() == status).count();
    }

    @Test
    @DisplayName("R10.1 available 10 인 상품에 수량 1 주문 20건 동시 요청 → 201 10건, 409 10건, reserved 10")
    void stockContention() throws Exception {
        long productId = createProduct(1_000, 10);

        List<Resp> responses = runConcurrently(20, () -> createOrder(uniqueUser(), null, List.of(item(productId, 1))));

        assertThat(count(responses, 201)).isEqualTo(10);
        assertThat(count(responses, 409)).isEqualTo(10);
        responses.stream().filter(r -> r.status() == 409).forEach(r -> assertProblem(r, 409, "INSUFFICIENT_STOCK"));
        assertThat(product(productId).path("reserved").asInt()).isEqualTo(10);
        assertThat(product(productId).path("available").asInt()).isZero();
    }

    @Test
    @DisplayName("R10.2 totalQuantity 5 쿠폰을 서로 다른 사용자 15명이 동시 사용 → 201 5건, 409 10건, usedCount 5")
    void couponContention() throws Exception {
        long productId = createProduct(1_000, 100);
        Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 100);
        body.put("totalQuantity", 5);
        String coupon = createCoupon(body);

        List<Resp> responses = runConcurrently(15, () -> createOrder(uniqueUser(), coupon, List.of(item(productId, 1))));

        assertThat(count(responses, 201)).isEqualTo(5);
        assertThat(count(responses, 409)).isEqualTo(10);
        responses.stream().filter(r -> r.status() == 409).forEach(r -> assertProblem(r, 409, "COUPON_EXHAUSTED"));
        assertThat(coupon(coupon).path("usedCount").asInt()).isEqualTo(5);
        assertThat(product(productId).path("reserved").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 주문 5건 동시 요청(키는 서로 다름) → 201 정확히 1건")
    void sameUserSameCoupon() throws Exception {
        long productId = createProduct(1_000, 100);
        String coupon = createCoupon("FIXED", 100);
        String user = uniqueUser();

        List<Resp> responses = runConcurrently(5, () -> createOrder(user, coupon, List.of(item(productId, 1))));

        assertThat(count(responses, 201)).isEqualTo(1);
        responses.stream().filter(r -> r.status() != 201).forEach(r -> assertProblem(r, 409, "COUPON_NOT_APPLICABLE"));
        assertThat(coupon(coupon).path("usedCount").asInt()).isEqualTo(1);
        assertThat(product(productId).path("reserved").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R10.4 [P, Q] 와 [Q, P] 순서 주문을 섞어 동시에 요청해도 5xx 없이 처리되고 reserved 가 정확하다")
    void crossOrderNoDeadlock() throws Exception {
        long p = createProduct(1_000, 15);
        long q = createProduct(2_000, 100);
        AtomicInteger seq = new AtomicInteger();
        Callable<Resp> task = () -> {
            List<Map<String, Object>> items = seq.getAndIncrement() % 2 == 0
                    ? List.of(item(p, 1), item(q, 1))
                    : List.of(item(q, 1), item(p, 1));
            return createOrder(uniqueUser(), null, items);
        };

        List<Resp> responses = runConcurrently(30, task);

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.raw()).isIn(201, 409));
        assertThat(count(responses, 201)).isEqualTo(15);
        assertThat(product(p).path("reserved").asInt()).isEqualTo(15);
        assertThat(product(q).path("reserved").asInt()).isEqualTo(15);
    }

    @Test
    @DisplayName("R10.4 교차 순서 주문과 취소·결제를 섞어도 5xx 없이 reserved·stock 이 정확하다")
    void crossOrderWithCancelAndPay() throws Exception {
        long p = createProduct(1_000, 1_000);
        long q = createProduct(1_000, 1_000);
        List<Long> existing = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            existing.add(placeOrder(item(q, 1), item(p, 1)).path("id").asLong());
        }
        AtomicInteger seq = new AtomicInteger();
        Callable<Resp> task = () -> {
            int n = seq.getAndIncrement();
            return switch (n % 4) {
                case 0 -> createOrder(uniqueUser(), null, List.of(item(p, 1), item(q, 2)));
                case 1 -> createOrder(uniqueUser(), null, List.of(item(q, 2), item(p, 1)));
                case 2 -> post("/api/orders/" + existing.get(n / 4) + "/cancel", null);
                default -> pay(existing.get(n / 4 + 5), "tok_ok");
            };
        };

        List<Resp> responses = runConcurrently(20, task);

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.raw()).isIn(200, 201));
        // 기존 10건 중 5건 취소, 5건 결제. 새 주문 10건(P 1개, Q 2개씩)
        assertThat(product(p).path("stock").asInt()).isEqualTo(1_000 - 5);
        assertThat(product(p).path("reserved").asInt()).isEqualTo(10);
        assertThat(product(q).path("stock").asInt()).isEqualTo(1_000 - 5);
        assertThat(product(q).path("reserved").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("R10.5 같은 주문에 결제가 동시에 와도(키는 서로 다름) PG 결제 요청은 최대 1번, 성공 응답은 1건")
    void concurrentPayments() throws Exception {
        long productId = createProduct(1_000, 10);
        long orderId = placeOrder(item(productId, 2)).path("id").asLong();

        List<Resp> responses = runConcurrently(10, () -> pay(orderId, "tok_slow_ok"));

        assertThat(count(responses, 200)).isEqualTo(1);
        responses.stream().filter(r -> r.status() != 200).forEach(r -> assertProblem(r, 409, "INVALID_STATE"));
        assertThat(pg.paymentCallsFor(orderId)).hasSize(1);
        assertThat(order(orderId).path("status").asText()).isEqualTo("PAID");
        assertThat(product(productId).path("stock").asInt()).isEqualTo(8);
        assertThat(product(productId).path("reserved").asInt()).isZero();
    }
}
