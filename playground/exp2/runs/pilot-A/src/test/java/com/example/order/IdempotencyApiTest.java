package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api.Response;
import com.example.order.support.Concurrently;
import com.example.order.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R4 멱등성")
class IdempotencyApiTest extends IntegrationTest {

    @Test
    @DisplayName("R4.1 Idempotency-Key가 없으면 주문 생성·결제 모두 400")
    void keyRequired() {
        long p = createProduct(1000, 10);
        assertProblem(api.post("/api/orders", orderBody(null, p, 1), "X-User-Id", uniqueUser()), 400,
                "VALIDATION_ERROR");
        long orderId = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        assertProblem(api.post("/api/orders/" + orderId + "/pay", "{\"cardToken\":\"tok\"}"), 400,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R4.2 같은 키·같은 요청은 다시 처리하지 않고 최초 응답을 그대로 돌려준다")
    void replayCreate() {
        long p = createProduct(1000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderBody(null, p, 2);

        Response first = postOrder(user, key, body);
        Response second = postOrder(user, key, body);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.rawBody()).isEqualTo(first.rawBody());
        assertThat(second.location()).isEqualTo(first.location());
        assertThat(product(p).get("reserved").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("R4.2 결제 재생: 상태가 바뀐 뒤에도 최초 응답 본문을 돌려주고 PG를 다시 호출하지 않는다")
    void replayPay() {
        long p = createProduct(1000, 10);
        long orderId = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        String key = uniqueKey();

        Response first = pay(orderId, key, "tok");
        api.post("/api/orders/" + orderId + "/ship", null);
        Response second = pay(orderId, key, "tok");

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.rawBody()).isEqualTo(first.rawBody());
        assertThat(second.body().get("status").asText()).isEqualTo("PAID");
        assertThat(PG.paymentCallsFor(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 다른 본문·사용자·경로면 422 IDEMPOTENCY_KEY_MISMATCH")
    void mismatch() {
        long p = createProduct(1000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(postOrder(user, key, orderBody(null, p, 1)).status()).isEqualTo(201);

        assertProblem(postOrder(user, key, orderBody(null, p, 2)), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(postOrder(uniqueUser(), key, orderBody(null, p, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");

        long a = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        long b = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        String payKey = uniqueKey();
        assertThat(pay(a, payKey, "tok").status()).isEqualTo(200);
        assertProblem(pay(b, payKey, "tok"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(pay(a, payKey, "other-token"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(order(b).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.1 주문 생성과 결제의 키 공간은 독립이다")
    void independentKeySpaces() {
        long p = createProduct(1000, 10);
        String key = uniqueKey();
        Response created = postOrder(uniqueUser(), key, orderBody(null, p, 1));
        assertThat(created.status()).isEqualTo(201);

        Response paid = pay(created.body().get("id").asLong(), key, "tok");
        assertThat(paid.status()).as(paid.toString()).isEqualTo(200);
    }

    @Test
    @DisplayName("R4.4 오류로 끝난 요청의 키는 같은 요청으로 다시 시도할 수 있다")
    void errorsAreNotStored() {
        long p = createProduct(1000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        String code = uniqueCouponCode();
        String body = orderBody(code, p, 1);

        assertProblem(postOrder(user, key, body), 404, "COUPON_NOT_FOUND");
        createCoupon(couponBody(code, "FIXED", 100));
        Response retried = postOrder(user, key, body);

        assertThat(retried.status()).as(retried.toString()).isEqualTo(201);
        assertThat(retried.body().get("couponCode").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("R4.4 PG 장애(503) 후 같은 키로 재시도하면 다시 처리된다")
    void retryAfterGatewayFailure() {
        long p = createProduct(1000, 10);
        long orderId = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        String key = uniqueKey();

        assertProblem(pay(orderId, key, "error"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertProblem(pay(orderId, key, "error"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(PG.paymentCallsFor(orderId)).hasSize(2);
    }

    @Test
    @DisplayName("R4.5 같은 키 요청이 동시에 와도 처리는 한 번, 나머지는 재생 또는 409 IDEMPOTENCY_IN_PROGRESS")
    void concurrentSameKey() {
        long p = createProduct(1000, 100);
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderBody(null, p, 1);

        List<Response> responses = Concurrently.run(10, i -> postOrder(user, key, body));

        List<Response> created = responses.stream().filter(r -> r.status() == 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(created.stream().map(Response::rawBody).distinct()).hasSize(1);
        responses.stream().filter(r -> r.status() != 201)
                .forEach(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(product(p).get("reserved").asLong()).isEqualTo(1);
        assertThat(api.get("/api/orders?userId=" + user).body().get("content")).hasSize(1);
    }

    @Test
    @DisplayName("R4.5 같은 키 결제가 동시에 와도 PG 결제 요청은 한 번")
    void concurrentSamePayKey() {
        long p = createProduct(1000, 10);
        long orderId = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        String key = uniqueKey();

        List<Response> responses = Concurrently.run(8, i -> pay(orderId, key, "tok"));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(200, 409));
        responses.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(responses.stream().filter(r -> r.status() == 200)).isNotEmpty();
        assertThat(PG.paymentCallsFor(orderId)).hasSize(1);
    }
}
