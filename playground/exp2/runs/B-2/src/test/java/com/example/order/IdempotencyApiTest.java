package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

/** R4. 멱등성 (주문 생성 · 결제) */
class IdempotencyApiTest extends IntegrationTestSupport {

    @Test
    void createOrder_sameRequestReplaysFirstResponse() {
        long p = createProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        Map<String, Object> body = orderBody(null, item(p, 2));

        Res first = postOrder(user, key, body);
        Res second = postOrder(user, key, body);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().getLocation()).isEqualTo(first.headers().getLocation());
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    void createOrder_replayReturnsOriginalResponseEvenAfterStateChange() {
        long p = createProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        Map<String, Object> body = orderBody(null, item(p, 1));
        Res first = postOrder(user, key, body);
        post("/api/orders/" + first.body().get("id").asLong() + "/cancel");

        Res replay = postOrder(user, key, body);

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.body().get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void createOrder_sameKeyDifferentRequest_returns422() {
        long p = createProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        postOrder(user, key, orderBody(null, item(p, 1)));

        assertProblem(postOrder(user, key, orderBody(null, item(p, 2))), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(postOrder(uniqueUser(), key, orderBody(null, item(p, 1))), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    void errorPrecedence_badRequestBeforeMismatch_andMismatchBeforeNotFound() {
        long p = createProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        postOrder(user, key, orderBody(null, item(p, 1)));

        assertProblem(postOrder(user, key, orderBody(null, item(p, 0))), 400, "VALIDATION_ERROR");
        assertProblem(postOrder(user, key, orderBody(null, item(999_999_999L, 1))), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    void failedRequestIsNotStored_andCanBeRetriedWithSameKey() {
        long p = createProduct(1_000, 1);
        long blocking = placeOrder(uniqueUser(), null, item(p, 1));
        String user = uniqueUser();
        String key = uniqueKey();
        Map<String, Object> body = orderBody(null, item(p, 1));

        assertProblem(postOrder(user, key, body), 409, "INSUFFICIENT_STOCK");
        post("/api/orders/" + blocking + "/cancel");

        Res retried = postOrder(user, key, body);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    void keySpacesOfCreateAndPayAreIndependent() {
        long p = createProduct(1_000, 10);
        String key = uniqueKey();
        Res created = postOrder(uniqueUser(), key, orderBody(null, item(p, 1)));

        Res paid = pay(created.body().get("id").asLong(), key);

        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.body().get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void pay_sameRequestReplaysFirstResponse_withoutCallingGatewayAgain() {
        long orderId = placeOrder(uniqueUser(), null, item(createProduct(1_000, 10), 1));
        String key = uniqueKey();

        Res first = pay(orderId, key);
        Res second = pay(orderId, key);

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(PG.payments()).hasSize(1);
    }

    @Test
    void pay_sameKeyDifferentRequest_returns422() {
        long orderId = placeOrder(uniqueUser(), null, item(createProduct(1_000, 10), 1));
        String key = uniqueKey();
        pay(orderId, key);

        Res otherToken = post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok_other"), Map.of("Idempotency-Key", key));
        assertProblem(otherToken, 422, "IDEMPOTENCY_KEY_MISMATCH");

        long otherOrder = placeOrder(uniqueUser(), null, item(createProduct(1_000, 10), 1));
        assertProblem(pay(otherOrder, key), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    void pay_gatewayFailureIsNotStored_andCanBeRetriedWithSameKey() {
        long orderId = placeOrder(uniqueUser(), null, item(createProduct(1_000, 10), 1));
        String key = uniqueKey();
        PG.paymentMode(FakePaymentGateway.Mode.SERVER_ERROR);
        assertProblem(pay(orderId, key), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        PG.paymentMode(FakePaymentGateway.Mode.APPROVE);
        Res retried = pay(orderId, key);

        assertThat(retried.status()).isEqualTo(200);
        assertThat(PG.payments()).extracting(FakePaymentGateway.PaymentCall::idempotencyKey).containsOnly(key);
    }

    @Test
    void concurrentRequestsWithSameKey_areProcessedOnce() throws Exception {
        long p = createProduct(1_000, 100);
        String user = uniqueUser();
        String key = uniqueKey();
        Map<String, Object> body = orderBody(null, item(p, 3));
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> postOrder(user, key, body));
        }

        List<Res> results = concurrently(tasks);

        List<Res> created = results.stream().filter(r -> r.status() == 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(created).extracting(r -> r.body().get("id").asLong()).containsOnly(created.getFirst().body().get("id").asLong());
        results.stream().filter(r -> r.status() != 201)
                .forEach(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(get("/api/orders?userId=" + user).body().get("content")).hasSize(1);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(3);
    }
}
