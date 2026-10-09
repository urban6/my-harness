package com.example.order.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.FakePaymentGateway;
import com.example.order.support.IntegrationTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R4 멱등성")
class IdempotencyApiTest extends IntegrationTest {

    @Test
    @DisplayName("R4.2 같은 키·같은 요청은 다시 처리하지 않고 최초 응답(상태·본문)을 돌려준다")
    void create_replaysFirstResponse() {
        long productId = createProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        Map<String, Object> body = orderBody(null, List.of(item(productId, 2)));

        ApiResponse first = placeOrder(user, key, body);
        ApiResponse second = placeOrder(user, key, body);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.header("Location")).isEqualTo(first.header("Location"));
        assertProduct(productId, 10, 2); // 한 번만 예약
    }

    @Test
    @DisplayName("R4.2 본문의 공백·필드 순서가 달라도 같은 본문이면 같은 요청이다")
    void create_sameBodyDifferentFormatting() {
        long productId = createProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();

        ApiResponse first = api.postRaw("/api/orders",
                "{\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}",
                "X-User-Id", user, "Idempotency-Key", key);
        ApiResponse second = api.postRaw("/api/orders",
                "{ \"items\" : [ { \"quantity\" : 1, \"productId\" : " + productId + " } ], \"couponCode\": null }",
                "X-User-Id", user, "Idempotency-Key", key);

        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
    }

    @Test
    @DisplayName("R4.3 같은 키로 본문이 다른 요청은 422 IDEMPOTENCY_KEY_MISMATCH")
    void create_mismatchedBody() {
        long productId = createProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        placeOrder(user, key, orderBody(null, List.of(item(productId, 1))));

        ApiResponse res = placeOrder(user, key, orderBody(null, List.of(item(productId, 2))));

        assertProblem(res, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProduct(productId, 10, 1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 X-User-Id 가 다른 요청은 422")
    void create_mismatchedUser() {
        long productId = createProduct(1_000, 10);
        String key = uniqueKey();
        Map<String, Object> body = orderBody(null, List.of(item(productId, 1)));
        placeOrder(uniqueUser(), key, body);

        assertProblem(placeOrder(uniqueUser(), key, body), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R4.4 오류로 끝난 요청의 키는 같은 요청으로 다시 시도할 수 있다")
    void errorResponsesAreNotStored() {
        long productId = createProduct(1_000, 10);
        String code = uniqueCouponCode();
        String user = uniqueUser();
        String key = uniqueKey();
        Map<String, Object> body = orderBody(code, List.of(item(productId, 1)));

        assertProblem(placeOrder(user, key, body), 404, "COUPON_NOT_FOUND");
        assertThat(api.post("/api/coupons", couponBody(code, Map.of())).status()).isEqualTo(201);

        ApiResponse retried = placeOrder(user, key, body);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body().get("couponCode").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("R4.1 주문 생성과 결제의 키 공간은 독립이다")
    void keySpacesAreIndependent() {
        long productId = createProduct(1_000, 10);
        String key = uniqueKey();
        ApiResponse created = placeOrder(uniqueUser(), key, orderBody(null, List.of(item(productId, 1))));

        ApiResponse paid = pay(created.id(), key, "tok_visa");

        assertThat(paid.status()).as(paid.toString()).isEqualTo(200);
        assertThat(paid.body().get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R4.1 결제도 Idempotency-Key 가 필수다(누락 시 400)")
    void pay_requiresKey() {
        long orderId = createOrder(uniqueUser(), null, List.of(item(createProduct(1_000, 1), 1)));

        ApiResponse res = api.post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok"));

        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R4.2 결제를 같은 키로 다시 보내면 PG 를 다시 호출하지 않고 최초 응답을 돌려준다")
    void pay_replaysFirstResponse() {
        long orderId = createOrder(uniqueUser(), null, List.of(item(createProduct(1_000, 5), 1)));
        String key = uniqueKey();

        ApiResponse first = pay(orderId, key, "tok_visa");
        ApiResponse second = pay(orderId, key, "tok_visa");

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(PG.paymentCalls()).hasSize(1);
    }

    @Test
    @DisplayName("R4.3 결제를 같은 키로 다른 주문·카드에 보내면 422")
    void pay_mismatch() {
        long productId = createProduct(1_000, 5);
        long order1 = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        long order2 = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        String key = uniqueKey();
        pay(order1, key, "tok_visa");

        assertProblem(pay(order2, key, "tok_visa"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(pay(order1, key, "tok_other"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(orderStatus(order2)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.4 PG 장애(503)로 끝난 결제는 같은 키로 재시도할 수 있다")
    void pay_retryAfterGatewayFailure() {
        long orderId = createOrder(uniqueUser(), null, List.of(item(createProduct(1_000, 5), 1)));
        String key = uniqueKey();
        PG.paymentMode(FakePaymentGateway.Mode.SERVER_ERROR);
        assertProblem(pay(orderId, key, "tok_visa"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        PG.paymentMode(FakePaymentGateway.Mode.APPROVE);
        ApiResponse retried = pay(orderId, key, "tok_visa");

        assertThat(retried.status()).isEqualTo(200);
        assertThat(PG.paymentCalls()).extracting(FakePaymentGateway.PaymentCall::idempotencyKey)
                .containsExactly(key, key);
    }

    @Test
    @DisplayName("R4.5 같은 키의 주문 생성이 동시에 오면 실제 처리는 한 번, 나머지는 재생 또는 409")
    void create_concurrentSameKey() throws Exception {
        long productId = createProduct(1_000, 100);
        String user = uniqueUser();
        String key = uniqueKey();
        Map<String, Object> body = orderBody(null, List.of(item(productId, 3)));
        List<Callable<ApiResponse>> tasks = new ArrayList<>(Collections.nCopies(10, () -> placeOrder(user, key, body)));

        List<ApiResponse> results = concurrently(tasks);

        List<ApiResponse> created = results.stream().filter(r -> r.status() == 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(created).extracting(ApiResponse::id).containsOnly(created.getFirst().id());
        results.stream().filter(r -> r.status() != 201)
                .forEach(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertProduct(productId, 100, 3);
    }

    @Test
    @DisplayName("R4.5 같은 키의 결제가 동시에 오면 PG 호출은 한 번, 나머지는 재생 또는 409")
    void pay_concurrentSameKey() throws Exception {
        long orderId = createOrder(uniqueUser(), null, List.of(item(createProduct(1_000, 5), 1)));
        String key = uniqueKey();
        PG.latency(java.time.Duration.ofMillis(300));
        List<Callable<ApiResponse>> tasks = new ArrayList<>(Collections.nCopies(8, () -> pay(orderId, key, "tok")));

        List<ApiResponse> results = concurrently(tasks);

        assertThat(results).filteredOn(r -> r.status() == 200).isNotEmpty();
        results.stream().filter(r -> r.status() != 200)
                .forEach(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(PG.paymentCalls()).hasSize(1);
    }
}
