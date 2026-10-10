package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Response;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** R4. 주문 생성·결제 멱등성. */
class OrderIdempotencyTest extends IntegrationTestBase {

    private int orderCountOf(String user) {
        return jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, user);
    }

    private ResponseEntity<JsonNode> payWithUser(long orderId, String key, String cardToken, String user) {
        return post("/api/orders/" + orderId + "/pay", map("cardToken", cardToken), "Idempotency-Key", key,
                "X-User-Id", user);
    }

    // ------------------------------------------------------------------ R4.1 필수·독립 키 공간

    @Test
    @DisplayName("R4.1 결제는 Idempotency-Key 헤더가 필수다 (누락 400, 65자 400)")
    void r4_1_payRequiresIdempotencyKey() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();

        ResponseEntity<JsonNode> missing = post("/api/orders/" + orderId + "/pay", map("cardToken", "tok_ok"));
        ResponseEntity<JsonNode> tooLong = pay(orderId, "k".repeat(65), "tok_ok");

        assertProblem(missing, 400, "VALIDATION_ERROR");
        assertProblem(tooLong, 400, "VALIDATION_ERROR");
        assertThat(PG.requests()).isEmpty();
        assertThat(statusOf(orderId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.1 생성에서 쓴 키를 결제에 써도 재생·충돌 없이 결제가 처리된다 (키 공간 독립)")
    void r4_1_createKeyCanBeReusedForPay() {
        long productId = newProduct(1000, 5);
        String sharedKey = uniqueKey();
        ResponseEntity<JsonNode> created = placeOrder(uniqueUser(), sharedKey, null, items(productId, 1));
        long orderId = created.getBody().get("id").asLong();

        ResponseEntity<JsonNode> paid = pay(orderId, sharedKey, "tok_ok");

        assertStatus(paid, 200);
        assertThat(paid.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
    }

    @Test
    @DisplayName("R4.1 결제에서 쓴 키를 주문 생성에 써도 새 주문이 만들어진다 (키 공간 독립)")
    void r4_1_payKeyCanBeReusedForCreate() {
        long productId = newProduct(1000, 5);
        long orderId = newOrder(productId, 1).get("id").asLong();
        String sharedKey = uniqueKey();
        assertStatus(pay(orderId, sharedKey, "tok_ok"), 200);

        ResponseEntity<JsonNode> created = placeOrder(uniqueUser(), sharedKey, null, items(productId, 1));

        assertStatus(created, 201);
        assertThat(created.getBody().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(created.getBody().get("id").asLong()).isNotEqualTo(orderId);
    }

    // ------------------------------------------------------------------ R4.2 재생

    @Test
    @DisplayName("R4.2 같은 키·같은 요청의 주문 생성은 최초 응답(201·본문·Location)을 재생하고 부작용은 1회뿐이다")
    void r4_2_createReplaysFirstResponse() {
        long productId = newProduct(1000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        ResponseEntity<JsonNode> first = placeOrder(user, key, null, items(productId, 3));

        ResponseEntity<JsonNode> replay = placeOrder(user, key, null, items(productId, 3));

        assertStatus(first, 201);
        assertThat(code(replay)).isEqualTo(201);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(orderCountOf(user)).isEqualTo(1);
        assertStock(productId, 10, 3);
    }

    @Test
    @DisplayName("R4.2 쿠폰 주문의 재생은 usedCount 를 다시 올리지 않는다")
    void r4_2_couponCreateReplayDoesNotConsumeCouponAgain() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        placeOrder(user, key, code, items(productId, 1));

        ResponseEntity<JsonNode> replay = placeOrder(user, key, code, items(productId, 1));

        assertStatus(replay, 201);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R4.2 재생은 주문 상태가 바뀐 뒤에도 최초 응답 본문을 돌려준다 (취소 후에도 PENDING_PAYMENT 본문)")
    void r4_2_createReplayReturnsOriginalBodyEvenAfterStateChange() {
        long productId = newProduct(1000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        ResponseEntity<JsonNode> first = placeOrder(user, key, null, items(productId, 2));
        assertStatus(cancel(first.getBody().get("id").asLong()), 200);

        ResponseEntity<JsonNode> replay = placeOrder(user, key, null, items(productId, 2));

        assertStatus(replay, 201);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getBody().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertStock(productId, 10, 0);
        assertThat(orderCountOf(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.2 같은 키·같은 요청의 결제는 최초 응답을 재생하고 PG 를 다시 호출하지 않는다")
    void r4_2_payReplaysFirstResponse() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 2).get("id").asLong();
        String key = uniqueKey();
        ResponseEntity<JsonNode> first = pay(orderId, key, "tok_ok");

        ResponseEntity<JsonNode> replay = pay(orderId, key, "tok_ok");

        assertStatus(first, 200);
        assertThat(code(replay)).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
        assertStock(productId, 8, 0);
    }

    @Test
    @DisplayName("R4.2 결제 재생은 이후 상태(SHIPPED)가 되어도 최초 응답(PAID)을 돌려준다")
    void r4_2_payReplayAfterShipStillReturnsOriginalBody() {
        long orderId = newOrder(newProduct(1000, 10), 1).get("id").asLong();
        String key = uniqueKey();
        ResponseEntity<JsonNode> first = pay(orderId, key, "tok_ok");
        assertStatus(ship(orderId), 200);

        ResponseEntity<JsonNode> replay = pay(orderId, key, "tok_ok");

        assertStatus(replay, 200);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getBody().get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R4.2 같은 X-User-Id 로 결제를 반복해도 재생된다")
    void r4_2_payReplayWithUserHeader() {
        long orderId = newOrder(newProduct(1000, 10), 1).get("id").asLong();
        String key = uniqueKey();
        String user = uniqueUser();
        ResponseEntity<JsonNode> first = payWithUser(orderId, key, "tok_ok", user);

        ResponseEntity<JsonNode> replay = payWithUser(orderId, key, "tok_ok", user);

        assertStatus(first, 200);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
    }

    // ------------------------------------------------------------------ R4.3 불일치

    @Test
    @DisplayName("R4.3 같은 키로 본문(수량)이 다른 주문 생성은 422 IDEMPOTENCY_KEY_MISMATCH, 부작용 없음")
    void r4_3_createWithDifferentQuantityIsMismatch() {
        long productId = newProduct(1000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        placeOrder(user, key, null, items(productId, 1));

        ResponseEntity<JsonNode> res = placeOrder(user, key, null, items(productId, 2));

        assertProblem(res, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertStock(productId, 10, 1);
        assertThat(orderCountOf(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 상품 또는 쿠폰이 다른 주문 생성은 422")
    void r4_3_createWithDifferentItemOrCouponIsMismatch() {
        long a = newProduct(1000, 10);
        long b = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        placeOrder(user, key, null, items(a, 1));

        assertProblem(placeOrder(user, key, null, items(b, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(placeOrder(user, key, code, items(a, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertStock(b, 10, 0);
        assertUsedCount(code, 0);
    }

    @Test
    @DisplayName("R4.3 같은 키로 X-User-Id 가 다른 주문 생성은 422")
    void r4_3_createWithDifferentUserIsMismatch() {
        long productId = newProduct(1000, 10);
        String key = uniqueKey();
        placeOrder(uniqueUser(), key, null, items(productId, 1));

        ResponseEntity<JsonNode> res = placeOrder(uniqueUser(), key, null, items(productId, 1));

        assertProblem(res, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertStock(productId, 10, 1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 다른 orderId 에 결제하면 422, 두 번째 주문은 결제되지 않는다")
    void r4_3_payWithDifferentOrderIsMismatch() {
        long productId = newProduct(1000, 10);
        long first = newOrder(productId, 1).get("id").asLong();
        long second = newOrder(productId, 1).get("id").asLong();
        String key = uniqueKey();
        assertStatus(pay(first, key, "tok_ok"), 200);

        ResponseEntity<JsonNode> res = pay(second, key, "tok_ok");

        assertProblem(res, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(statusOf(second)).isEqualTo("PENDING_PAYMENT");
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 cardToken(본문)이 다른 결제는 422")
    void r4_3_payWithDifferentCardTokenIsMismatch() {
        long orderId = newOrder(newProduct(1000, 10), 1).get("id").asLong();
        String key = uniqueKey();
        assertStatus(pay(orderId, key, "tok_a"), 200);

        assertProblem(pay(orderId, key, "tok_b"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 X-User-Id 가 다른 결제는 422")
    void r4_3_payWithDifferentUserIsMismatch() {
        long orderId = newOrder(newProduct(1000, 10), 1).get("id").asLong();
        String key = uniqueKey();
        assertStatus(payWithUser(orderId, key, "tok_ok", uniqueUser()), 200);

        assertProblem(payWithUser(orderId, key, "tok_ok", uniqueUser()), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
    }

    // ------------------------------------------------------------------ R4.4 오류는 저장되지 않는다

    @Test
    @DisplayName("R4.4 재고 부족 409 로 끝난 키는 재고가 생긴 뒤 같은 요청으로 다시 성공한다")
    void r4_4_createRetryableAfterInsufficientStock() {
        long productId = newProduct(1000, 1);
        long holder = newOrder(productId, 1).get("id").asLong();
        String user = uniqueUser();
        String key = uniqueKey();
        assertProblem(placeOrder(user, key, null, items(productId, 1)), 409, "INSUFFICIENT_STOCK");
        assertStatus(cancel(holder), 200);

        ResponseEntity<JsonNode> retry = placeOrder(user, key, null, items(productId, 1));

        assertStatus(retry, 201);
        assertThat(retry.getBody().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertStock(productId, 1, 1);
        assertThat(orderCountOf(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.4 쿠폰 404 로 끝난 키는 쿠폰을 만든 뒤 같은 요청으로 다시 성공한다")
    void r4_4_createRetryableAfterCouponNotFound() {
        long productId = newProduct(1000, 5);
        String code = uniqueCode();
        String user = uniqueUser();
        String key = uniqueKey();
        assertProblem(placeOrder(user, key, code, items(productId, 1)), 404, "COUPON_NOT_FOUND");
        createCoupon(couponBody(code, "FIXED", 100));

        ResponseEntity<JsonNode> retry = placeOrder(user, key, code, items(productId, 1));

        assertStatus(retry, 201);
        assertThat(retry.getBody().get("couponCode").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("R4.4 쿠폰 부적용 409 로 끝난 키도 저장되지 않아 최초 오류가 재생되지 않는다")
    void r4_4_couponNotApplicableIsNotReplayed() {
        long productId = newProduct(1000, 5);
        String code = newCoupon("FIXED", 100, 1);
        String user = uniqueUser();
        long first = newOrder(user, code, items(productId, 1)).get("id").asLong();
        String key = uniqueKey();
        assertProblem(placeOrder(user, key, code, items(productId, 1)), 409, "COUPON_NOT_APPLICABLE");
        assertStatus(cancel(first), 200);

        ResponseEntity<JsonNode> retry = placeOrder(user, key, code, items(productId, 1));

        assertStatus(retry, 201);
    }

    @Test
    @DisplayName("R4.4 400 으로 끝난 요청은 키를 점유하지 않는다 (같은 키로 다른 본문을 보내도 422 가 아니다)")
    void r4_4_validationErrorDoesNotOccupyKey() {
        long productId = newProduct(1000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        assertProblem(placeOrder(user, key, null, items(productId, 0)), 400, "VALIDATION_ERROR");

        ResponseEntity<JsonNode> res = placeOrder(user, key, null, items(productId, 2));

        assertStatus(res, 201);
    }

    @Test
    @DisplayName("R4.4 PG 장애 503 으로 끝난 결제의 키는 같은 요청으로 다시 시도할 수 있고 PG 에는 같은 키가 전달된다")
    void r4_4_payRetryableAfterGatewayFailure() {
        long productId = newProduct(1000, 5);
        long orderId = newOrder(productId, 1).get("id").asLong();
        String key = uniqueKey();
        PG.respondWith(r -> Response.status(503));
        assertProblem(pay(orderId, key, "tok_ok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        PG.reset();

        ResponseEntity<JsonNode> retry = pay(orderId, key, "tok_ok");

        assertStatus(retry, 200);
        assertThat(retry.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
        assertThat(PG.requests().get(0).header("Idempotency-Key")).isEqualTo(key);
        assertStock(productId, 4, 0);
    }

    @Test
    @DisplayName("R4.4 PG 5xx 가 두 번 이어져도 각 시도는 PG 로 전달된다 (503 이 저장·재생되지 않는다)")
    void r4_4_gatewayFailureIsNotReplayed() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        String key = uniqueKey();
        PG.respondWith(r -> Response.status(500));

        assertProblem(pay(orderId, key, "tok_ok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertProblem(pay(orderId, key, "tok_ok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(PG.requestsTo("/v1/payments")).hasSize(2);
    }

    @Test
    @DisplayName("R4.4 거절 402 는 저장되지 않는다 (같은 키 재시도는 402 재생이 아니라 새로 평가되어 409 INVALID_STATE)")
    void r4_4_declinedPaymentIsNotReplayed() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        String key = uniqueKey();
        assertProblem(pay(orderId, key, "decline_me"), 402, "PAYMENT_DECLINED");

        ResponseEntity<JsonNode> retry = pay(orderId, key, "decline_me");

        assertProblem(retry, 409, "INVALID_STATE");
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
    }

    // ------------------------------------------------------------------ R4.5 동시 동일 키

    @Test
    @DisplayName("R4.5 같은 키·같은 요청의 동시 주문 생성 10건은 주문 1건만 만들고 나머지는 재생 또는 409 IDEMPOTENCY_IN_PROGRESS")
    void r4_5_concurrentSameKeyCreateProcessesOnce() {
        long productId = newProduct(1000, 100);
        String user = uniqueUser();
        String key = uniqueKey();

        List<ResponseEntity<JsonNode>> results = concurrently(10,
                i -> placeOrder(user, key, null, items(productId, 2)));

        Set<Long> orderIds = new HashSet<>();
        JsonNode firstBody = null;
        for (ResponseEntity<JsonNode> r : results) {
            if (code(r) == 201) {
                orderIds.add(r.getBody().get("id").asLong());
                if (firstBody == null) {
                    firstBody = r.getBody();
                }
                assertThat(r.getBody()).isEqualTo(firstBody);
            } else {
                assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS");
            }
        }
        assertThat(count(results, 201)).isGreaterThanOrEqualTo(1);
        assertThat(orderIds).hasSize(1);
        assertThat(orderCountOf(user)).isEqualTo(1);
        assertStock(productId, 100, 2);
    }

    @Test
    @DisplayName("R4.5 같은 키·같은 요청의 동시 결제 10건은 PG 결제 요청 1회, 나머지는 재생 또는 409 IDEMPOTENCY_IN_PROGRESS")
    void r4_5_concurrentSameKeyPayProcessesOnce() {
        long productId = newProduct(1000, 100);
        long orderId = newOrder(productId, 2).get("id").asLong();
        String key = uniqueKey();
        PG.respondWith(r -> r.path().equals("/v1/payments")
                ? Response.json("{\"paymentId\":\"pay-conc\",\"status\":\"APPROVED\"}").delayed(400)
                : Response.status(404));

        List<ResponseEntity<JsonNode>> results = concurrently(10, i -> pay(orderId, key, "tok_ok"));

        JsonNode firstBody = null;
        for (ResponseEntity<JsonNode> r : results) {
            if (code(r) == 200) {
                if (firstBody == null) {
                    firstBody = r.getBody();
                }
                assertThat(r.getBody()).isEqualTo(firstBody);
            } else {
                assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS");
            }
        }
        assertThat(count(results, 200)).isGreaterThanOrEqualTo(1);
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
        assertStock(productId, 98, 0);
        assertThat(statusOf(orderId)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R4.5 PG 호출이 진행 중일 때 같은 키 요청이 들어오면 409 IDEMPOTENCY_IN_PROGRESS 이고 PG 는 1번만 호출되며, 최초 요청은 정상 완료된다")
    void r4_5_sameKeyWhilePaymentInFlightReturnsInProgress() throws Exception {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 1).get("id").asLong();
        String key = uniqueKey();
        PG.respondWith(r -> Response.json("{\"paymentId\":\"pay-inflight\",\"status\":\"APPROVED\"}").delayed(1200));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ResponseEntity<JsonNode>> first = pool.submit(() -> pay(orderId, key, "tok_ok"));
            awaitGatewayRequests(1); // 최초 요청이 키를 선점하고 PG 호출에 들어갔다

            ResponseEntity<JsonNode> duplicate = pay(orderId, key, "tok_ok");

            assertProblem(duplicate, 409, "IDEMPOTENCY_IN_PROGRESS");
            ResponseEntity<JsonNode> firstResult = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertStatus(firstResult, 200);
            assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
            assertStock(productId, 9, 0);
        } finally {
            pool.shutdownNow();
        }
    }
}
