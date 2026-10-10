package com.example.order;

import static com.example.order.support.TestApi.coupon;
import static com.example.order.support.TestApi.item;
import static com.example.order.support.TestApi.newCouponCode;
import static com.example.order.support.TestApi.newKey;
import static com.example.order.support.TestApi.newUserId;
import static com.example.order.support.TestApi.orderBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Mode;
import com.example.order.support.IntegrationTest;
import com.example.order.support.TestApi.Response;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R4 멱등성")
class IdempotencyApiTest extends IntegrationTest {

    @Test
    @DisplayName("R4.1 주문 생성·결제는 Idempotency-Key 필수")
    void keyRequired() {
        long productId = api.createProduct(1000, 10);
        api.post("/api/orders", orderBody(null, List.of(item(productId, 1))), Map.of("X-User-Id", newUserId()))
                .assertProblem(400, "VALIDATION_ERROR");
        long orderId = api.createOrderId(newUserId(), productId, 1);
        api.post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok"))
                .assertProblem(400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R4.1 키 공간은 주문 생성과 결제가 서로 독립")
    void keySpacesAreIndependent() {
        long productId = api.createProduct(1000, 10);
        String key = newKey();
        long orderId = api.createOrder(newUserId(), key, orderBody(null, List.of(item(productId, 1))))
                .assertStatus(201).id();

        api.pay(orderId, key, "tok").assertStatus(200);
    }

    @Test
    @DisplayName("R4.2 같은 키·같은 요청 → 재처리 없이 최초와 같은 상태코드·본문")
    void sameKeySameRequest_replays() {
        long productId = api.createProduct(1000, 10);
        String user = newUserId();
        String key = newKey();
        Map<String, Object> body = orderBody(null, List.of(item(productId, 2)));

        Response first = api.createOrder(user, key, body).assertStatus(201);
        Response second = api.createOrder(user, key, body).assertStatus(201);

        assertThat(second.rawBody()).isEqualTo(first.rawBody());
        assertThat(second.header("Location")).isEqualTo(first.header("Location"));
        assertThat(api.product(productId).get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("R4.2 결제도 같은 키면 최초 응답을 재생하고 PG를 다시 부르지 않음")
    void payReplay() {
        long productId = api.createProduct(1000, 10);
        long orderId = api.createOrderId(newUserId(), productId, 1);
        String key = newKey();

        Response first = api.pay(orderId, key, "tok").assertStatus(200);
        Response second = api.pay(orderId, key, "tok").assertStatus(200);

        assertThat(second.rawBody()).isEqualTo(first.rawBody());
        assertThat(PG.paymentCalls()).hasSize(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 다른 요청(본문·사용자·경로) → 422 IDEMPOTENCY_KEY_MISMATCH")
    void sameKeyDifferentRequest_returns422() {
        long productId = api.createProduct(1000, 10);
        String user = newUserId();
        String key = newKey();
        api.createOrder(user, key, orderBody(null, List.of(item(productId, 1)))).assertStatus(201);

        api.createOrder(user, key, orderBody(null, List.of(item(productId, 2))))
                .assertProblem(422, "IDEMPOTENCY_KEY_MISMATCH");
        api.createOrder(newUserId(), key, orderBody(null, List.of(item(productId, 1))))
                .assertProblem(422, "IDEMPOTENCY_KEY_MISMATCH");

        long order1 = api.createOrderId(user, productId, 1);
        long order2 = api.createOrderId(user, productId, 1);
        String payKey = newKey();
        api.pay(order1, payKey, "tok").assertStatus(200);
        api.pay(order2, payKey, "tok").assertProblem(422, "IDEMPOTENCY_KEY_MISMATCH");
        api.pay(order1, payKey, "other").assertProblem(422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(api.product(productId).get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("R4.4 오류로 끝난 요청의 키는 같은 요청으로 다시 시도할 수 있음")
    void failedRequest_isNotStored() {
        long productId = api.createProduct(1000, 10);
        String code = newCouponCode();
        String user = newUserId();
        String key = newKey();
        Map<String, Object> body = orderBody(code, List.of(item(productId, 1)));

        api.createOrder(user, key, body).assertProblem(404, "COUPON_NOT_FOUND");
        api.createCoupon(coupon(code, "FIXED", 100));
        api.createOrder(user, key, body).assertStatus(201);

        long orderId = api.createOrderId(user, productId, 1);
        String payKey = newKey();
        PG.paymentMode(Mode.SERVER_ERROR);
        api.pay(orderId, payKey, "tok").assertProblem(503, "PAYMENT_GATEWAY_UNAVAILABLE");
        PG.paymentMode(Mode.APPROVE);
        api.pay(orderId, payKey, "tok").assertStatus(200);
    }

    @Test
    @DisplayName("R4.5 같은 키 동시 요청 → 실제 처리는 한 번, 나머지는 재생 또는 409 IDEMPOTENCY_IN_PROGRESS")
    void concurrentSameKey_processedOnce() throws Exception {
        long productId = api.createProduct(1000, 100);
        String user = newUserId();
        String key = newKey();
        Map<String, Object> body = orderBody(null, List.of(item(productId, 3)));

        List<Callable<Response>> tasks = IntStream.range(0, 10)
                .<Callable<Response>>mapToObj(i -> () -> api.createOrder(user, key, body))
                .toList();
        List<Response> responses = runConcurrently(tasks);

        List<Response> created = responses.stream().filter(r -> r.status() == 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(created).extracting(Response::rawBody).containsOnly(created.getFirst().rawBody());
        responses.stream().filter(r -> r.status() != 201)
                .forEach(r -> r.assertProblem(409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(api.product(productId).get("reserved").asInt()).isEqualTo(3);
    }

}
