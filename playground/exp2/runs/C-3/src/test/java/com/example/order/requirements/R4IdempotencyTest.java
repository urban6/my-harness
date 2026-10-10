package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R4. 멱등성 (주문 생성 · 결제)")
class R4IdempotencyTest extends IntegrationTestBase {

    // ---------------------------------------------------------------- R4.1

    @Test
    @DisplayName("R4.1 주문 생성에 Idempotency-Key 가 없으면 400")
    void r4_1_createWithoutKey_returns400() {
        long productId = product(1_000, 5);

        ResponseEntity<String> r = post("/api/orders", orderBody(null, item(productId, 1)), "X-User-Id", uid("u"));

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("R4.1 결제에 Idempotency-Key 가 없으면 400 이고 PG 는 호출되지 않는다")
    void r4_1_payWithoutKey_returns400() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));

        ResponseEntity<String> r = post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok"));

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(PG.paymentCalls()).isZero();
    }

    @Test
    @DisplayName("R4.1 결제의 Idempotency-Key 가 65자이면 400")
    void r4_1_payKey65chars_returns400() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));

        ResponseEntity<String> r = pay(orderId, "k".repeat(65), "tok");

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R4.1 주문 생성과 결제의 키 공간은 독립이다 (같은 키 문자열을 양쪽에 써도 각각 처리된다)")
    void r4_1_keySpacesAreIndependent() {
        long productId = product(1_000, 5);
        String shared = uid("shared");

        ResponseEntity<String> created = createOrder(uid("u"), shared, orderBody(null, item(productId, 1)));
        long orderId = json(created).get("id").asLong();
        ResponseEntity<String> paid = pay(orderId, shared, "tok");

        assertThat(statusOf(created)).isEqualTo(201);
        assertThat(statusOf(paid)).isEqualTo(200);
        assertThat(json(paid).get("status").asText()).isEqualTo("PAID");
        assertThat(PG.paymentCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.1 결제에 쓴 키를 다른 주문 생성에 써도 새 주문이 만들어진다")
    void r4_1_payKeyDoesNotBlockOrderCreate() {
        long productId = product(1_000, 5);
        String shared = uid("shared");
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        pay(orderId, shared, "tok");

        ResponseEntity<String> created = createOrder(uid("u"), shared, orderBody(null, item(productId, 2)));

        assertThat(statusOf(created)).isEqualTo(201);
        assertThat(json(created).get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- R4.2

    @Test
    @DisplayName("R4.2 주문 생성 재전송은 최초와 같은 상태·본문·Location 을 돌려준다")
    void r4_2_createReplay_returnsSameStatusBodyAndLocation() {
        long productId = product(1_000, 5);
        String user = uid("u");
        String key = uid("k");
        Object body = orderBody(null, item(productId, 2));

        ResponseEntity<String> first = createOrder(user, key, body);
        ResponseEntity<String> replay = createOrder(user, key, body);

        assertThat(statusOf(replay)).isEqualTo(statusOf(first)).isEqualTo(201);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
    }

    @Test
    @DisplayName("R4.2 공백·필드 순서만 다른 같은 JSON 본문은 같은 요청으로 보아 최초 응답을 재생한다")
    void r4_2_semanticallyEqualJson_isReplayed() {
        long productId = product(1_000, 5);
        String user = uid("u");
        String key = uid("k");
        String compact = "{\"items\":[{\"productId\":" + productId + ",\"quantity\":2}]}";
        String reformatted = "{ \"items\" : [ { \"quantity\" : 2 , \"productId\" : " + productId + " } ] , \"couponCode\" : null }";

        ResponseEntity<String> first = createOrder(user, key, compact);
        ResponseEntity<String> replay = createOrder(user, key, reformatted);

        assertThat(statusOf(replay)).isEqualTo(201);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(reservedOf(productId)).isEqualTo(2);
    }

    @Test
    @DisplayName("R4.2 주문 생성을 재전송해도 재고는 한 번만 예약되고 주문도 하나다")
    void r4_2_createReplay_processesOnlyOnce() {
        long productId = product(1_000, 5);
        String user = uid("u");
        String key = uid("k");
        Object body = orderBody(null, item(productId, 2));

        createOrder(user, key, body);
        createOrder(user, key, body);
        createOrder(user, key, body);

        assertThat(reservedOf(productId)).isEqualTo(2);
        assertThat(json(get("/api/orders?userId=" + user)).get("content")).hasSize(1);
    }

    @Test
    @DisplayName("R4.2 쿠폰 주문을 재전송해도 usedCount 는 1 이고 재생은 409 가 아니라 최초 201 이다")
    void r4_2_couponCreateReplay_isNotTreatedAsSecondUse() {
        long productId = product(1_000, 5);
        String code = uniqueCode("IDEM");
        createCoupon(code, "FIXED", 100, 0, null, 5);
        String user = uid("u");
        String key = uid("k");
        Object body = orderBody(code, item(productId, 1));

        ResponseEntity<String> first = createOrder(user, key, body);
        ResponseEntity<String> replay = createOrder(user, key, body);

        assertThat(statusOf(replay)).isEqualTo(201);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R4.2 결제 재전송은 최초와 같은 상태·본문을 돌려주고 PG 는 다시 호출되지 않는다")
    void r4_2_payReplay_returnsSameResponseWithoutCallingPgAgain() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 2)));
        String key = uid("pk");

        ResponseEntity<String> first = pay(orderId, key, "tok_a");
        ResponseEntity<String> replay = pay(orderId, key, "tok_a");

        assertThat(statusOf(first)).isEqualTo(200);
        assertThat(statusOf(replay)).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(PG.paymentCalls()).isEqualTo(1);
        assertThat(stockOf(productId)).isEqualTo(3);
    }

    @Test
    @DisplayName("R4.2 결제 재생은 주문이 이미 PAID 여도 409 INVALID_STATE 가 아니라 최초 200 이다")
    void r4_2_payReplay_afterOrderIsPaid_isStill200() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String key = uid("pk");
        pay(orderId, key, "tok_a");
        ship(orderId);

        ResponseEntity<String> replay = pay(orderId, key, "tok_a");

        assertThat(statusOf(replay)).isEqualTo(200);
        assertThat(json(replay).get("status").asText()).isEqualTo("PAID");
    }

    // ---------------------------------------------------------------- R4.3

    @Test
    @DisplayName("R4.3 같은 키로 다른 본문(수량)을 보내면 422 IDEMPOTENCY_KEY_MISMATCH")
    void r4_3_createWithDifferentBody_returns422() {
        long productId = product(1_000, 5);
        String user = uid("u");
        String key = uid("k");
        createOrder(user, key, orderBody(null, item(productId, 1)));

        ResponseEntity<String> r = createOrder(user, key, orderBody(null, item(productId, 2)));

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(reservedOf(productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 쿠폰 코드만 다르게 보내도 422")
    void r4_3_createWithDifferentCoupon_returns422() {
        long productId = product(1_000, 5);
        String code = uniqueCode("MIS");
        createCoupon(code, "FIXED", 100, 0, null, 5);
        String user = uid("u");
        String key = uid("k");
        createOrder(user, key, orderBody(null, item(productId, 1)));

        ResponseEntity<String> r = createOrder(user, key, orderBody(code, item(productId, 1)));

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(usedCountOf(code)).isZero();
    }

    @Test
    @DisplayName("R4.3 같은 키·같은 본문이라도 다른 X-User-Id 이면 422")
    void r4_3_createWithDifferentUser_returns422() {
        long productId = product(1_000, 5);
        String key = uid("k");
        Object body = orderBody(null, item(productId, 1));
        createOrder(uid("userA"), key, body);

        ResponseEntity<String> r = createOrder(uid("userB"), key, body);

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(reservedOf(productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 항목 순서를 바꿔 보내면 422 (다른 요청)")
    void r4_3_createWithReorderedItems_returns422() {
        long p1 = product(1_000, 5);
        long p2 = product(1_000, 5);
        String user = uid("u");
        String key = uid("k");
        createOrder(user, key, orderBody(null, item(p1, 1), item(p2, 1)));

        ResponseEntity<String> r = createOrder(user, key, orderBody(null, item(p2, 1), item(p1, 1)));

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R4.3 결제에서 같은 키로 cardToken 을 바꾸면 422 이고 PG 재호출은 없다")
    void r4_3_payWithDifferentCardToken_returns422() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String key = uid("pk");
        pay(orderId, key, "tok_a");

        ResponseEntity<String> r = pay(orderId, key, "tok_b");

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(PG.paymentCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 결제에서 같은 키를 다른 주문에 쓰면 422 (경로가 다른 요청)")
    void r4_3_payWithDifferentOrder_returns422() {
        long productId = product(1_000, 5);
        long order1 = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        long order2 = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String key = uid("pk");
        pay(order1, key, "tok_a");

        ResponseEntity<String> r = pay(order2, key, "tok_a");

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(orderStatus(order2)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.3 결제에서 같은 키로 X-User-Id 헤더가 달라지면 422")
    void r4_3_payWithDifferentUserHeader_returns422() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String key = uid("pk");
        postWithKey("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok"), key, "X-User-Id", "alice");

        ResponseEntity<String> r = postWithKey("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok"), key,
                "X-User-Id", "bob");

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    // ---------------------------------------------------------------- R4.4

    @Test
    @DisplayName("R4.4 404 로 끝난 주문 생성의 키는 같은 요청으로 다시 시도하면 새로 처리된다")
    void r4_4_create404_isNotStored_retrySucceedsAfterFix() {
        long productId = product(1_000, 5);
        String code = uniqueCode("LATE");
        String user = uid("u");
        String key = uid("k");
        Object body = orderBody(code, item(productId, 1));
        assertProblem(createOrder(user, key, body), 404, "COUPON_NOT_FOUND");
        createCoupon(code, "FIXED", 100, 0, null, 5);

        ResponseEntity<String> retry = createOrder(user, key, body);

        assertThat(statusOf(retry)).isEqualTo(201);
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R4.4 재고 부족(409)으로 끝난 주문 생성의 키는 재고가 생긴 뒤 같은 요청으로 다시 시도할 수 있다")
    void r4_4_create409_isNotStored_retrySucceedsAfterStockFreed() {
        long productId = product(1_000, 1);
        long blocker = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String user = uid("u");
        String key = uid("k");
        Object body = orderBody(null, item(productId, 1));
        assertProblem(createOrder(user, key, body), 409, "INSUFFICIENT_STOCK");
        cancel(blocker);

        ResponseEntity<String> retry = createOrder(user, key, body);

        assertThat(statusOf(retry)).isEqualTo(201);
    }

    @Test
    @DisplayName("R4.4 400 으로 끝난 요청은 키를 소비하지 않는다 (유효한 본문으로 같은 키를 쓸 수 있다)")
    void r4_4_create400_doesNotConsumeKey() {
        long productId = product(1_000, 5);
        String user = uid("u");
        String key = uid("k");
        assertProblem(createOrder(user, key, orderBody(null, item(productId, 0))), 400, "VALIDATION_ERROR");

        ResponseEntity<String> r = createOrder(user, key, orderBody(null, item(productId, 1)));

        assertThat(statusOf(r)).isEqualTo(201);
    }

    @Test
    @DisplayName("R4.4 PG 장애(503)로 끝난 결제의 키는 복구 후 같은 요청으로 다시 시도하면 결제된다")
    void r4_4_pay503_isNotStored_retrySucceedsAfterRecovery() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String key = uid("pk");
        PG.failPaymentsWith5xx();
        assertProblem(pay(orderId, key, "tok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        PG.approvePayments();

        ResponseEntity<String> retry = pay(orderId, key, "tok");

        assertThat(statusOf(retry)).isEqualTo(200);
        assertThat(json(retry).get("status").asText()).isEqualTo("PAID");
        assertThat(PG.paymentCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("R4.4 거절(402)된 결제의 키는 저장되지 않는다 (재시도하면 402 재생이 아니라 주문 상태 기준 409)")
    void r4_4_pay402_isNotReplayed() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String key = uid("pk");
        PG.declinePayments();
        assertProblem(pay(orderId, key, "tok"), 402, "PAYMENT_DECLINED");

        ResponseEntity<String> retry = pay(orderId, key, "tok");

        assertProblem(retry, 409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.4 결제 400(cardToken 공백)은 키를 소비하지 않는다")
    void r4_4_pay400_doesNotConsumeKey() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String key = uid("pk");
        assertProblem(pay(orderId, key, "  "), 400, "VALIDATION_ERROR");

        ResponseEntity<String> r = pay(orderId, key, "tok");

        assertThat(statusOf(r)).isEqualTo(200);
    }

    @Test
    @DisplayName("R4.4 결제 404 로 끝난 키는 소비되지 않는다 (같은 키를 존재하는 다른 주문에 쓸 수 있다)")
    void r4_4_pay404_doesNotConsumeKey() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String key = uid("pk");
        assertProblem(pay(987_654_321L, key, "tok"), 404, "ORDER_NOT_FOUND");

        ResponseEntity<String> r = pay(orderId, key, "tok");

        assertThat(statusOf(r)).isEqualTo(200);
    }

    // ---------------------------------------------------------------- R4.5

    @Test
    @DisplayName("R4.5 처리 중인 결제와 같은 키·같은 요청이 오면 409 IDEMPOTENCY_IN_PROGRESS")
    void r4_5_requestWhileInProgress_returns409InProgress() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String key = uid("pk");
        PG.delayPayments(1_200); // 지연 후 APPROVED 로 응답 (2초 타임아웃 이내)
        CompletableFuture<ResponseEntity<String>> first = CompletableFuture.supplyAsync(() -> pay(orderId, key, "tok"));
        assertThat(awaitCondition(java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(20),
                () -> PG.paymentCalls() >= 1)).as("첫 요청이 PG 에 도달").isTrue();

        ResponseEntity<String> concurrent = pay(orderId, key, "tok");
        ResponseEntity<String> firstResult = first.join();

        assertProblem(concurrent, 409, "IDEMPOTENCY_IN_PROGRESS");
        assertThat(statusOf(firstResult)).isEqualTo(200);
        assertThat(PG.paymentCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.5 첫 요청이 끝난 뒤 도착한 같은 키 요청은 409 가 아니라 최초 응답의 재생이다")
    void r4_5_requestAfterCompletion_replaysFirstResponse() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        String key = uid("pk");
        PG.delayPayments(600);
        ResponseEntity<String> first = pay(orderId, key, "tok");

        ResponseEntity<String> later = pay(orderId, key, "tok");

        assertThat(statusOf(later)).isEqualTo(200);
        assertThat(later.getBody()).isEqualTo(first.getBody());
    }

    @Test
    @DisplayName("R4.5 같은 키로 주문 생성을 동시에 보내면 실제 처리는 한 번이고 나머지는 재생 또는 409 IN_PROGRESS")
    void r4_5_concurrentCreateWithSameKey_processesOnce() {
        long productId = product(1_000, 50);
        String user = uid("u");
        String key = uid("k");
        Object body = orderBody(null, item(productId, 1));

        List<ResponseEntity<String>> results = runConcurrently(10, i -> createOrder(user, key, body));

        List<ResponseEntity<String>> created = results.stream().filter(r -> statusOf(r) == 201).toList();
        List<ResponseEntity<String>> others = results.stream().filter(r -> statusOf(r) != 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(created).extracting(ResponseEntity::getBody).containsOnly(created.get(0).getBody());
        assertThat(others).allSatisfy(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(reservedOf(productId)).isEqualTo(1);
        assertThat(json(get("/api/orders?userId=" + user)).get("content")).hasSize(1);
    }

    @Test
    @DisplayName("R4.5 같은 키로 결제를 동시에 보내면 PG 호출은 1번이고 나머지는 재생 또는 409 IN_PROGRESS")
    void r4_5_concurrentPayWithSameKey_callsPgOnce() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 2)));
        String key = uid("pk");
        PG.delayPayments(500);

        List<ResponseEntity<String>> results = runConcurrently(8, i -> pay(orderId, key, "tok"));

        List<ResponseEntity<String>> ok = results.stream().filter(r -> statusOf(r) == 200).toList();
        List<ResponseEntity<String>> others = results.stream().filter(r -> statusOf(r) != 200).toList();
        assertThat(ok).isNotEmpty();
        assertThat(ok).extracting(ResponseEntity::getBody).containsOnly(ok.get(0).getBody());
        assertThat(others).allSatisfy(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(PG.paymentCalls()).isEqualTo(1);
        JsonNode p = json(getProduct(productId));
        assertThat(p.get("stock").asInt()).isEqualTo(3);
        assertThat(p.get("reserved").asInt()).isZero();
    }
}
