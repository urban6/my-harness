package com.example.order;

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

@DisplayName("R4. 멱등성")
class IdempotencyTest extends IntegrationTest {

    @Test
    @DisplayName("R4.1 Idempotency-Key 는 주문 생성·결제 모두 필수")
    void keyRequired() {
        long productId = createProduct(1_000, 10);
        Resp noKeyCreate = post("/api/orders", orderBody(null, List.of(item(productId, 1))), Map.of("X-User-Id", uniqueUser()));
        assertProblem(noKeyCreate, 400, "VALIDATION_ERROR");

        long orderId = placeOrder(item(productId, 1)).path("id").asLong();
        Resp noKeyPay = post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok_ok"));
        assertProblem(noKeyPay, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R4.1 두 엔드포인트의 키 공간은 독립이다")
    void independentKeySpaces() {
        long productId = createProduct(1_000, 10);
        String key = uniqueKey();
        Resp created = createOrder(uniqueUser(), key, orderBody(null, List.of(item(productId, 1))));
        assertThat(created.status()).isEqualTo(201);

        Resp paid = pay(created.id(), key, "tok_ok");

        assertThat(paid.status()).as(paid.raw()).isEqualTo(200);
        assertThat(paid.json().path("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R4.2 같은 키로 같은 요청이 다시 오면 처리하지 않고 최초 응답을 그대로 돌려준다 (주문 생성)")
    void replayCreate() {
        long productId = createProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        Map<String, Object> body = orderBody(null, List.of(item(productId, 2)));

        Resp first = createOrder(user, key, body);
        Resp second = createOrder(user, key, body);
        // 필드 순서·공백이 달라도 같은 본문이다
        Resp third = createOrder(user, key, "{ \"items\" : [ { \"quantity\": 2, \"productId\": " + productId + " } ] }");

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(third.status()).isEqualTo(201);
        assertThat(second.raw()).isEqualTo(first.raw());
        assertThat(third.raw()).isEqualTo(first.raw());
        assertThat(second.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
        assertThat(product(productId).path("reserved").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("R4.2 최초 응답 이후 주문 상태가 바뀌어도 재생 응답은 최초 응답과 같다 (결제)")
    void replayPay() {
        long productId = createProduct(1_000, 10);
        long orderId = placeOrder(item(productId, 1)).path("id").asLong();
        String key = uniqueKey();

        Resp first = pay(orderId, key, "tok_ok");
        assertThat(post("/api/orders/" + orderId + "/ship", null).status()).isEqualTo(200);
        Resp second = pay(orderId, key, "tok_ok");

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.raw()).isEqualTo(first.raw());
        assertThat(second.json().path("status").asText()).isEqualTo("PAID");
        assertThat(pg.paymentCallsFor(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 다른 본문·다른 사용자·다른 경로면 422 IDEMPOTENCY_KEY_MISMATCH")
    void mismatch() {
        long productId = createProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(createOrder(user, key, orderBody(null, List.of(item(productId, 1)))).status()).isEqualTo(201);

        assertProblem(createOrder(user, key, orderBody(null, List.of(item(productId, 2)))), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(createOrder(uniqueUser(), key, orderBody(null, List.of(item(productId, 1)))), 422, "IDEMPOTENCY_KEY_MISMATCH");

        long order1 = placeOrder(item(productId, 1)).path("id").asLong();
        long order2 = placeOrder(item(productId, 1)).path("id").asLong();
        String payKey = uniqueKey();
        assertThat(pay(order1, payKey, "tok_ok").status()).isEqualTo(200);
        assertProblem(pay(order2, payKey, "tok_ok"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(pay(order1, payKey, "tok_other"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(order(order2).path("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("C3. 400 은 멱등 키 검사보다, 422 는 404 보다 먼저")
    void precedence() {
        long productId = createProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(createOrder(user, key, orderBody(null, List.of(item(productId, 1)))).status()).isEqualTo(201);

        assertProblem(createOrder(user, key, orderBody(null, List.of(item(productId, 0)))), 400, "VALIDATION_ERROR");
        assertProblem(createOrder(user, key, orderBody(null, List.of(item(987654321L, 1)))), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R4.4 오류로 끝난 요청의 키는 같은 요청으로 다시 시도할 수 있다")
    void errorsAreNotStored() {
        long productId = createProduct(1_000, 1);
        long blocking = placeOrder(item(productId, 1)).path("id").asLong();
        String user = uniqueUser();
        String key = uniqueKey();
        Map<String, Object> body = orderBody(null, List.of(item(productId, 1)));

        assertProblem(createOrder(user, key, body), 409, "INSUFFICIENT_STOCK");
        assertThat(post("/api/orders/" + blocking + "/cancel", null).status()).isEqualTo(200);
        Resp retried = createOrder(user, key, body);

        assertThat(retried.status()).isEqualTo(201);
        assertThat(product(productId).path("reserved").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.4 PG 장애(503)로 끝난 결제 키는 같은 요청으로 다시 시도할 수 있다")
    void payRetryAfterGatewayFailure() {
        long productId = createProduct(1_000, 10);
        long orderId = placeOrder(item(productId, 1)).path("id").asLong();
        String key = uniqueKey();

        assertProblem(pay(orderId, key, "tok_500"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        // 키가 저장되지 않았으므로 재생(또는 409)이 아니라 다시 실제로 처리되어 PG 가 한 번 더 호출된다
        assertProblem(pay(orderId, key, "tok_500"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(pg.paymentCallsFor(orderId)).hasSize(2);
        assertThat(order(orderId).path("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.5 같은 키의 요청이 동시에 오면 실제 처리는 한 번, 나머지는 최초 응답 재생이거나 409 IDEMPOTENCY_IN_PROGRESS")
    void concurrentSameKey() throws Exception {
        long productId = createProduct(1_000, 100);
        String user = uniqueUser();
        String key = uniqueKey();
        Map<String, Object> body = orderBody(null, List.of(item(productId, 1)));

        List<Resp> responses = runConcurrently(20, () -> createOrder(user, key, body));

        List<Resp> created = responses.stream().filter(r -> r.status() == 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(created).extracting(Resp::raw).containsOnly(created.getFirst().raw());
        responses.stream().filter(r -> r.status() != 201)
                .forEach(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(product(productId).path("reserved").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.5 같은 키의 결제가 동시에 와도 PG 호출·결제 처리는 한 번")
    void concurrentSameKeyPay() throws Exception {
        long productId = createProduct(1_000, 100);
        long orderId = placeOrder(item(productId, 1)).path("id").asLong();
        String key = uniqueKey();

        List<Resp> responses = runConcurrently(10, () -> pay(orderId, key, "tok_slow_ok"));

        List<Resp> ok = responses.stream().filter(r -> r.status() == 200).toList();
        assertThat(ok).isNotEmpty();
        assertThat(ok).extracting(Resp::raw).containsOnly(ok.getFirst().raw());
        responses.stream().filter(r -> r.status() != 200)
                .forEach(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(pg.paymentCallsFor(orderId)).hasSize(1);
        assertThat(product(productId).path("stock").asInt()).isEqualTo(99);
    }

    static <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
