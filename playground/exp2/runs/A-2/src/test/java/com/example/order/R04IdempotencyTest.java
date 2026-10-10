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

@DisplayName("R4 멱등성")
class R04IdempotencyTest extends IntegrationTest {

    @Test
    @DisplayName("R4.1 Idempotency-Key가 없으면 주문 생성·결제 모두 400")
    void keyRequired() {
        long p = createProduct(1000, 10);
        long orderId = placeOrderOk("u1", null, p, 1);
        assertProblem(createOrder("u1", null, orderBody(null, p, 1)), 400, "VALIDATION_ERROR");
        assertProblem(pay(orderId, null, "card"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R4.2 같은 키·같은 요청은 다시 처리하지 않고 최초 응답을 그대로 돌려준다")
    void replaysCreate() {
        long p = createProduct(1000, 10);
        String key = newKey();

        Resp first = createOrder("u1", key, orderBody(null, p, 2));
        Resp second = createOrder("u1", key, orderBody(null, p, 2));

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.json()).isEqualTo(first.json());
        assertThat(second.header("Location")).isEqualTo(first.header("Location"));
        assertThat(product(p).path("reserved").asInt()).isEqualTo(2);
        assertThat(api.get("/api/orders").json().path("content")).hasSize(1);
    }

    @Test
    @DisplayName("R4.2 결제도 같은 키면 PG를 다시 부르지 않고 최초 응답을 돌려준다")
    void replaysPay() {
        long p = createProduct(1000, 10);
        long orderId = placeOrderOk("u1", null, p, 1);
        String key = newKey();

        Resp first = pay(orderId, key, "card-1");
        Resp second = pay(orderId, key, "card-1");

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json()).isEqualTo(first.json());
        assertThat(PG.paymentRequests()).hasSize(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 다른 요청(본문·사용자·경로)이면 422")
    void mismatch() {
        long p = createProduct(1000, 10);
        long q = createProduct(1000, 10);
        String key = newKey();
        assertThat(createOrder("u1", key, orderBody(null, p, 1)).status()).isEqualTo(201);

        assertProblem(createOrder("u1", key, orderBody(null, p, 2)), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(createOrder("u2", key, orderBody(null, p, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");

        long o1 = placeOrderOk("u1", null, q, 1);
        long o2 = placeOrderOk("u2", null, q, 1);
        String payKey = newKey();
        assertThat(pay(o1, payKey, "card").status()).isEqualTo(200);
        assertProblem(pay(o1, payKey, "other-card"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(pay(o2, payKey, "card"), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R4.1 두 엔드포인트의 키 공간은 독립이다")
    void independentKeySpaces() {
        long p = createProduct(1000, 10);
        String key = newKey();
        Resp created = createOrder("u1", key, orderBody(null, p, 1));
        assertThat(created.status()).isEqualTo(201);
        assertThat(pay(created.id(), key, "card").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R4.4 오류로 끝난 요청의 키는 같은 요청으로 다시 시도할 수 있다")
    void errorsAreNotStored() {
        long p = createProduct(1000, 10);
        String key = newKey();
        Map<String, Object> body = orderBody("LATER", p, 1);

        assertProblem(createOrder("u1", key, body), 404, "COUPON_NOT_FOUND");
        createCoupon(couponBody("LATER", "FIXED", 100));
        assertThat(createOrder("u1", key, body).status()).isEqualTo(201);

        long orderId = placeOrderOk("u1", null, p, 1);
        String payKey = newKey();
        PG.paymentBehavior(com.example.order.support.FakePaymentGateway.Behavior.SERVER_ERROR);
        assertProblem(pay(orderId, payKey, "card"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        PG.paymentBehavior(com.example.order.support.FakePaymentGateway.Behavior.APPROVE);
        assertThat(pay(orderId, payKey, "card").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("C3 400이 멱등 키 검사보다, 멱등 키 검사가 404보다 먼저다")
    void errorPrecedence() {
        long p = createProduct(1000, 10);
        String key = newKey();
        assertThat(createOrder("u1", key, orderBody(null, p, 1)).status()).isEqualTo(201);

        assertProblem(createOrder("u1", key, orderBody(null, p, 0)), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("u1", key, orderBody(null, 999_999, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R4.5 같은 키의 요청이 동시에 와도 실제 처리는 한 번")
    void concurrentSameKey() throws Exception {
        long p = createProduct(1000, 100);
        String key = newKey();
        int n = 10;
        List<Resp> responses = runConcurrently(n, () -> createOrder("u1", key, orderBody(null, p, 1)));

        List<Resp> created = responses.stream().filter(r -> r.status() == 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(created).allSatisfy(r -> assertThat(r.json()).isEqualTo(created.get(0).json()));
        responses.stream().filter(r -> r.status() != 201)
                .forEach(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(product(p).path("reserved").asInt()).isEqualTo(1);
        assertThat(api.get("/api/orders").json().path("content")).hasSize(1);
    }

    @Test
    @DisplayName("R4.5 같은 키의 결제가 동시에 와도 PG 결제는 한 번")
    void concurrentSamePayKey() throws Exception {
        long p = createProduct(1000, 100);
        long orderId = placeOrderOk("u1", null, p, 1);
        PG.paymentDelayMs(300);
        String key = newKey();

        List<Resp> responses = runConcurrently(8, () -> pay(orderId, key, "card"));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(200, 409));
        responses.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(PG.paymentRequests()).hasSize(1);
        assertThat(order(orderId).path("status").asText()).isEqualTo("PAID");
    }

    static List<Resp> runConcurrently(int n, Callable<Resp> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
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
}
