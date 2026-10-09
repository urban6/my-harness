package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R4. 멱등성")
class IdempotencyApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("R4.1 주문 생성·결제에 Idempotency-Key가 없으면 400")
    void keyRequired() {
        long p = createProduct(1_000, 5);
        assertProblem(post("/api/orders", orderBody(null, item(p, 1)), "X-User-Id", newUser()), 400, "VALIDATION_ERROR");
        long orderId = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        assertProblem(post("/api/orders/" + orderId + "/pay", map("cardToken", "card-ok")), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R4.1 두 엔드포인트의 키 공간은 서로 독립이다")
    void independentKeySpaces() {
        long p = createProduct(1_000, 5);
        String key = newKey();
        ResponseEntity<JsonNode> created = createOrder(newUser(), key, orderBody(null, item(p, 1)));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<JsonNode> paid = pay(created.getBody().get("id").asLong(), key, "card-ok");
        assertThat(paid.getStatusCode().value()).isEqualTo(200);
        assertThat(paid.getBody().get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R4.2 같은 키·같은 요청이면 처리하지 않고 최초 응답(상태코드·본문)을 돌려준다")
    void replayCreate() {
        long p = createProduct(1_000, 5);
        String user = newUser();
        String key = newKey();

        ResponseEntity<JsonNode> first = createOrder(user, key, orderBody(null, item(p, 2)));
        ResponseEntity<JsonNode> second = createOrder(user, key, orderBody(null, item(p, 2)));

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("R4.2 결제 재요청은 최초 응답을 돌려주고 PG를 다시 호출하지 않는다")
    void replayPay() {
        long p = createProduct(1_000, 5);
        long orderId = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        String key = newKey();

        ResponseEntity<JsonNode> first = pay(orderId, key, "card-ok");
        ResponseEntity<JsonNode> second = pay(orderId, key, "card-ok");

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(PG.paymentCallsFor(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 다른 본문·다른 사용자·다른 경로면 422")
    void mismatch() {
        long p = createProduct(1_000, 5);
        String user = newUser();
        String key = newKey();
        assertThat(createOrder(user, key, orderBody(null, item(p, 1))).getStatusCode().value()).isEqualTo(201);

        assertProblem(createOrder(user, key, orderBody(null, item(p, 2))), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(createOrder(newUser(), key, orderBody(null, item(p, 1))), 422, "IDEMPOTENCY_KEY_MISMATCH");

        long order1 = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        long order2 = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        String payKey = newKey();
        assertThat(pay(order1, payKey, "card-ok").getStatusCode().value()).isEqualTo(200);
        assertProblem(pay(order2, payKey, "card-ok"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(pay(order1, payKey, "card-other"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        // 첫 주문과 order2만 결제 대기이고, 422 요청들은 아무것도 바꾸지 않았다
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);
        assertThat(PG.paymentCallsFor(order2)).isEmpty();
    }

    @Test
    @DisplayName("R4.4 오류로 끝난 요청의 키는 같은 요청으로 다시 시도할 수 있다")
    void errorsAreNotStored() {
        long p = createProduct(1_000, 5);
        String user = newUser();
        String key = newKey();
        String code = newCouponCode();

        assertProblem(createOrder(user, key, orderBody(code, item(p, 1))), 404, "COUPON_NOT_FOUND");
        assertThat(post("/api/coupons", couponRequest(code)).getStatusCode().value()).isEqualTo(201);

        ResponseEntity<JsonNode> retried = createOrder(user, key, orderBody(code, item(p, 1)));
        assertThat(retried.getStatusCode().value()).isEqualTo(201);
        assertThat(retried.getBody().get("couponCode").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("R4.4 PG 장애(503)로 끝난 결제 키는 같은 요청으로 다시 시도할 수 있다")
    void failedPaymentKeyCanBeRetried() {
        long p = createProduct(1_000, 5);
        long orderId = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        String key = newKey();
        assertProblem(pay(orderId, key, "error-card"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        // 같은 요청 재시도: 저장된 응답이 없으므로 다시 처리된다(PG가 다시 호출된다)
        assertProblem(pay(orderId, key, "error-card"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(PG.paymentCallsFor(orderId)).hasSize(2);
    }

    @Test
    @DisplayName("R4.5 같은 키의 요청이 동시에 오면 실제 처리는 한 번, 나머지는 재생 또는 409 IDEMPOTENCY_IN_PROGRESS")
    void concurrentSameKey() {
        long p = createProduct(1_000, 100);
        String user = newUser();
        String key = newKey();

        List<ResponseEntity<JsonNode>> responses = concurrently(10,
                i -> createOrder(user, key, orderBody(null, item(p, 3))));

        Set<Long> createdIds = responses.stream()
                .filter(r -> r.getStatusCode().value() == 201)
                .map(r -> r.getBody().get("id").asLong())
                .collect(Collectors.toSet());
        assertThat(createdIds).hasSize(1);
        for (ResponseEntity<JsonNode> response : responses) {
            if (response.getStatusCode().value() != 201) {
                assertProblem(response, 409, "IDEMPOTENCY_IN_PROGRESS");
            }
        }
        assertThat(product(p).get("reserved").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("R4.5 같은 키의 결제가 동시에 와도 PG 결제는 한 번")
    void concurrentSamePayKey() {
        long p = createProduct(1_000, 5);
        long orderId = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        String key = newKey();

        List<ResponseEntity<JsonNode>> responses = concurrently(8, i -> pay(orderId, key, "slow-card"));

        assertThat(responses).anyMatch(r -> r.getStatusCode().value() == 200);
        for (ResponseEntity<JsonNode> response : responses) {
            if (response.getStatusCode().value() != 200) {
                assertProblem(response, 409, "IDEMPOTENCY_IN_PROGRESS");
            }
        }
        assertThat(PG.paymentCallsFor(orderId)).hasSize(1);
        assertThat(product(p).get("stock").asInt()).isEqualTo(4);
    }
}
