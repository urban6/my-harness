package com.example.order;

import com.example.order.support.Api.Resp;
import com.example.order.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R10 동시성")
class R10ConcurrencyTest extends IntegrationTest {

    @Test
    @DisplayName("R10.1 available 10에 수량 1 주문 20건 동시 → 201 10건, 409 10건, reserved 10")
    void stockRace() throws Exception {
        long p = createProduct(1000, 10);

        List<Resp> responses = runConcurrently(20, i -> placeOrder("user-" + i, null, p, 1));

        assertThat(count(responses, 201)).isEqualTo(10);
        assertThat(count(responses, 409)).isEqualTo(10);
        responses.stream().filter(r -> r.status() == 409).forEach(r -> assertProblem(r, 409, "INSUFFICIENT_STOCK"));
        assertThat(product(p).path("reserved").asInt()).isEqualTo(10);
        assertThat(product(p).path("available").asInt()).isZero();
    }

    @Test
    @DisplayName("R10.2 totalQuantity 5 쿠폰을 15명이 동시 사용 → 201 5건, 409 10건, usedCount 5")
    void couponQuantityRace() throws Exception {
        long p = createProduct(1000, 1000);
        Map<String, Object> body = couponBody("RUSH5", "FIXED", 100);
        body.put("totalQuantity", 5);
        createCoupon(body);

        List<Resp> responses = runConcurrently(15, i -> placeOrder("user-" + i, "RUSH5", p, 1));

        assertThat(count(responses, 201)).isEqualTo(5);
        assertThat(count(responses, 409)).isEqualTo(10);
        responses.stream().filter(r -> r.status() == 409).forEach(r -> assertProblem(r, 409, "COUPON_EXHAUSTED"));
        assertThat(coupon("RUSH5").path("usedCount").asInt()).isEqualTo(5);
        assertThat(product(p).path("reserved").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 5건 동시 주문 → 정확히 1건 201")
    void sameUserCouponRace() throws Exception {
        long p = createProduct(1000, 1000);
        createCoupon(couponBody("SOLO", "FIXED", 100));

        List<Resp> responses = runConcurrently(5, i -> placeOrder("same-user", "SOLO", p, 1));

        assertThat(count(responses, 201)).isEqualTo(1);
        responses.stream().filter(r -> r.status() != 201)
                .forEach(r -> assertProblem(r, 409, "COUPON_NOT_APPLICABLE"));
        assertThat(coupon("SOLO").path("usedCount").asInt()).isEqualTo(1);
        assertThat(product(p).path("reserved").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R10.4 [P,Q]와 [Q,P] 주문을 섞어 동시 요청해도 5xx 없이 처리되고 reserved가 정확하다")
    void noDeadlock() throws Exception {
        long p = createProduct(1000, 1000);
        long q = createProduct(2000, 1000);

        List<Resp> responses = runConcurrently(30, i -> i % 2 == 0
                ? placeOrder("user-" + i, null, p, 1, q, 2)
                : placeOrder("user-" + i, null, q, 2, p, 1));

        assertThat(count(responses, 201)).isEqualTo(30);
        assertThat(product(p).path("reserved").asInt()).isEqualTo(30);
        assertThat(product(q).path("reserved").asInt()).isEqualTo(60);
    }

    @Test
    @DisplayName("R10.4 재고가 모자라는 경합에서도 5xx 없이 reserved가 정확하다")
    void noDeadlockWhenContended() throws Exception {
        long p = createProduct(1000, 7);
        long q = createProduct(2000, 7);

        List<Resp> responses = runConcurrently(20, i -> i % 2 == 0
                ? placeOrder("user-" + i, null, p, 1, q, 1)
                : placeOrder("user-" + i, null, q, 1, p, 1));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
        assertThat(count(responses, 201)).isEqualTo(7);
        assertThat(product(p).path("reserved").asInt()).isEqualTo(7);
        assertThat(product(q).path("reserved").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("R10.5 같은 주문에 키가 다른 결제가 동시에 와도 PG 결제는 최대 1번, 성공 1건")
    void concurrentPayments() throws Exception {
        long p = createProduct(1000, 10);
        long orderId = placeOrderOk("u1", null, p, 1);
        PG.paymentDelayMs(300);

        List<Resp> responses = runConcurrently(10, i -> pay(orderId, "pay-key-" + i, "card"));

        assertThat(count(responses, 200)).isEqualTo(1);
        responses.stream().filter(r -> r.status() != 200).forEach(r -> assertProblem(r, 409, "INVALID_STATE"));
        assertThat(PG.paymentRequests()).hasSizeLessThanOrEqualTo(1);
        assertThat(order(orderId).path("status").asText()).isEqualTo("PAID");
        assertThat(product(p).path("stock").asInt()).isEqualTo(9);
        assertThat(product(p).path("reserved").asInt()).isZero();
    }

    interface IndexedCall {
        Resp call(int index) throws Exception;
    }

    private static List<Resp> runConcurrently(int n, IndexedCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                Callable<Resp> task = () -> {
                    ready.countDown();
                    start.await();
                    return call.call(index);
                };
                futures.add(pool.submit(task));
            }
            ready.await();
            start.countDown();
            List<Resp> responses = new ArrayList<>();
            for (Future<Resp> f : futures) {
                responses.add(f.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<Resp> responses, int status) {
        return responses.stream().filter(r -> r.status() == status).count();
    }
}
