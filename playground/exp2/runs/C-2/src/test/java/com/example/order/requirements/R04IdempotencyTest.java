package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R4. 멱등성 (주문 생성 · 결제) */
class R04IdempotencyTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------------ R4.1

    @Test
    @DisplayName("R4.1 주문 생성은 Idempotency-Key 헤더가 필수다 (누락 400)")
    void r4_1_create_requiresKey() {
        long productId = newProduct(1_000, 5);

        ApiResponse r = postOrder(uniqueUser(), null, orderJson(null, line(productId, 1)));

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R4.1 결제는 Idempotency-Key 헤더가 필수다 (누락 400, PG 호출 없음)")
    void r4_1_pay_requiresKey() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();

        ApiResponse r = postPay(orderId, null, "{\"cardToken\":\"tok\"}");

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(PG.paymentCallCount()).isZero();
        assertThat(getOrder(orderId).text("status")).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.1 키 공간은 두 엔드포인트가 독립이다 (같은 키 문자열로 생성과 결제가 각각 처리되고 각각 재생된다)")
    void r4_1_keySpaces_areIndependent() {
        String sharedKey = uniqueKey();
        long productId = newProduct(1_000, 5);
        String user = uniqueUser();
        String body = orderJson(null, line(productId, 1));

        ApiResponse created = postOrder(user, sharedKey, body);
        ApiResponse paid = pay(created.id(), sharedKey, "tok");
        ApiResponse createdReplay = postOrder(user, sharedKey, body);
        ApiResponse paidReplay = pay(created.id(), sharedKey, "tok");

        assertThat(created.status()).isEqualTo(201);
        assertThat(paid.status()).isEqualTo(200);
        assertThat(createdReplay.status()).isEqualTo(201);
        assertThat(createdReplay.body()).isEqualTo(created.body());
        assertThat(paidReplay.status()).isEqualTo(200);
        assertThat(paidReplay.body()).isEqualTo(paid.body());
        assertThat(PG.paymentCalls()).hasSize(1);
        assertThat(PG.paymentCalls().get(0).idempotencyKey()).isEqualTo(sharedKey);
    }

    @Test
    @DisplayName("R4.1 결제에 쓴 키를 주문 생성에 써도 충돌하지 않는다")
    void r4_1_payKey_canBeUsedForCreate() {
        String key = uniqueKey();
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();
        assertThat(pay(orderId, key, "tok").status()).isEqualTo(200);

        ApiResponse created = postOrder(uniqueUser(), key, orderJson(null, line(newProduct(1_000, 5), 1)));

        assertThat(created.status()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ R4.2

    @Test
    @DisplayName("R4.2 같은 키·같은 요청의 주문 생성은 최초와 같은 상태코드·본문·Location을 돌려주고 다시 처리하지 않는다")
    void r4_2_create_replay_sameResponse_noReprocessing() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderJson(coupon, line(productId, 2));

        ApiResponse first = postOrder(user, key, body);
        ApiResponse replay = postOrder(user, key, body);
        ApiResponse replay2 = postOrder(user, key, body);

        assertThat(first.status()).isEqualTo(201);
        for (ApiResponse r : List.of(replay, replay2)) {
            assertThat(r.status()).isEqualTo(201);
            assertThat(r.body()).isEqualTo(first.body());
            assertThat(r.header("Location")).isEqualTo(first.header("Location"));
        }
        assertThat(reserved(productId)).isEqualTo(2);
        assertThat(usedCount(coupon)).isEqualTo(1);
        assertThat(listOrders("userId=" + user).json().get("content")).hasSize(1);
    }

    @Test
    @DisplayName("R4.2 같은 키·같은 요청의 결제는 최초 200 본문을 돌려주고 PG를 다시 호출하지 않는다")
    void r4_2_pay_replay_sameResponse_noReprocessing() {
        long productId = newProduct(1_000, 10);
        long orderId = placeOrderOk(productId, 3).id();
        String key = uniqueKey();

        ApiResponse first = pay(orderId, key, "tok_same");
        ApiResponse replay = pay(orderId, key, "tok_same");

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(PG.paymentCallCount()).isEqualTo(1);
        assertThat(stock(productId)).isEqualTo(7);
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R4.2 주문 상태가 그 사이 바뀌어도 재생은 최초 응답(스냅샷)을 돌려준다")
    void r4_2_pay_replay_returnsOriginalSnapshot_afterStateChange() {
        long orderId = placeOrderOk(newProduct(1_000, 10), 1).id();
        String key = uniqueKey();
        ApiResponse first = pay(orderId, key, "tok");
        assertThat(ship(orderId).text("status")).isEqualTo("SHIPPED");

        ApiResponse replay = pay(orderId, key, "tok");

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.text("status")).isEqualTo("PAID");
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(getOrder(orderId).text("status")).isEqualTo("SHIPPED");
    }

    @Test
    @DisplayName("R4.2 재고가 소진된 뒤에도 같은 요청의 재생은 최초 201이다 (재처리하지 않음)")
    void r4_2_create_replay_afterStockExhausted() {
        long productId = newProduct(1_000, 1);
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderJson(null, line(productId, 1));
        ApiResponse first = postOrder(user, key, body);

        ApiResponse replay = postOrder(user, key, body);

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(reserved(productId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ R4.3

    @Test
    @DisplayName("R4.3 같은 키에 다른 본문(수량)으로 주문 생성하면 422 IDEMPOTENCY_KEY_MISMATCH이고 상태는 변하지 않는다")
    void r4_3_create_differentQuantity_returns422() {
        long productId = newProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        postOrder(user, key, orderJson(null, line(productId, 2)));

        ApiResponse r = postOrder(user, key, orderJson(null, line(productId, 3)));

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(reserved(productId)).isEqualTo(2);
    }

    @Test
    @DisplayName("R4.3 같은 키에 다른 상품·다른 쿠폰·다른 X-User-Id로 주문 생성하면 각각 422이다")
    void r4_3_create_differentProductCouponOrUser_returns422() {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(postOrder(user, key, orderJson(null, line(p1, 1))).status()).isEqualTo(201);

        assertThat(postOrder(user, key, orderJson(null, line(p2, 1))).code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(postOrder(user, key, orderJson(coupon, line(p1, 1))).code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(postOrder(uniqueUser(), key, orderJson(null, line(p1, 1))).status()).isEqualTo(422);
        assertThat(usedCount(coupon)).isZero();
    }

    @Test
    @DisplayName("R4.3 같은 키에 다른 cardToken으로 결제하면 422이고 PG를 다시 호출하지 않는다")
    void r4_3_pay_differentCardToken_returns422() {
        long orderId = placeOrderOk(newProduct(1_000, 10), 1).id();
        String key = uniqueKey();
        pay(orderId, key, "tok_a");

        ApiResponse r = pay(orderId, key, "tok_b");

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(PG.paymentCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 다른 주문(경로)을 결제하면 422이고 두 번째 주문은 결제되지 않는다")
    void r4_3_pay_differentOrderPath_returns422() {
        long productId = newProduct(1_000, 10);
        long a = placeOrderOk(productId, 1).id();
        long b = placeOrderOk(productId, 1).id();
        String key = uniqueKey();
        assertThat(pay(a, key, "tok").status()).isEqualTo(200);

        ApiResponse r = pay(b, key, "tok");

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(getOrder(b).text("status")).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.3 결제에서 X-User-Id가 달라져도 같은 키면 다른 요청이므로 422이다")
    void r4_3_pay_differentUserId_returns422() {
        long orderId = placeOrderOk(newProduct(1_000, 10), 1).id();
        String key = uniqueKey();
        post("/api/orders/" + orderId + "/pay", headers("Idempotency-Key", key, "X-User-Id", "alice"),
                "{\"cardToken\":\"tok\"}");

        ApiResponse r = post("/api/orders/" + orderId + "/pay", headers("Idempotency-Key", key, "X-User-Id", "bob"),
                "{\"cardToken\":\"tok\"}");

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    // ------------------------------------------------------------------ R4.4

    @Test
    @DisplayName("R4.4 재고 409로 끝난 주문 생성의 키는 같은 요청으로 다시 시도할 수 있다 (재고 확보 후 201)")
    void r4_4_create_afterStockConflict_sameKeyRetrySucceeds() {
        long productId = newProduct(1_000, 2);
        ApiResponse holder = placeOrderOk(productId, 1);
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderJson(null, line(productId, 2));
        assertProblem(postOrder(user, key, body), 409, "INSUFFICIENT_STOCK");

        cancel(holder.id());
        ApiResponse retry = postOrder(user, key, body);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(reserved(productId)).isEqualTo(2);
    }

    @Test
    @DisplayName("R4.4 404로 끝난 주문 생성의 키는 같은 요청으로 다시 시도할 수 있다 (쿠폰 등록 후 201)")
    void r4_4_create_afterNotFound_sameKeyRetrySucceeds() {
        long productId = newProduct(1_000, 5);
        String coupon = uniqueCouponCode();
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderJson(coupon, line(productId, 1));
        assertProblem(postOrder(user, key, body), 404, "COUPON_NOT_FOUND");

        assertThat(postCoupon(couponJson(coupon, "FIXED", 100, 0, null, 5)).status()).isEqualTo(201);
        ApiResponse retry = postOrder(user, key, body);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.text("couponCode")).isEqualTo(coupon);
    }

    @Test
    @DisplayName("R4.4 400으로 끝난 요청의 키는 소비되지 않는다 (잘못된 본문 뒤 같은 키로 올바른 요청이 201)")
    void r4_4_create_afterValidationError_keyNotConsumed() {
        long productId = newProduct(1_000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(postOrder(user, key, orderJson(null, line(productId, 0))).status()).isEqualTo(400);

        ApiResponse ok = postOrder(user, key, orderJson(null, line(productId, 1)));

        assertThat(ok.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R4.4 PG 장애(503)로 끝난 결제의 키는 같은 요청으로 다시 시도할 수 있고 PG에는 같은 Idempotency-Key가 간다")
    void r4_4_pay_afterGatewayFailure_sameKeyRetrySucceeds() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();
        String key = uniqueKey();
        PG.http500();
        assertProblem(pay(orderId, key, "tok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        PG.approve();
        ApiResponse retry = pay(orderId, key, "tok");

        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.text("status")).isEqualTo("PAID");
        assertThat(PG.paymentCalls()).hasSize(2);
        assertThat(PG.paymentCalls()).allSatisfy(c -> assertThat(c.idempotencyKey()).isEqualTo(key));
    }

    @Test
    @DisplayName("R4.4 거절(402)은 저장되지 않는다 (같은 요청 재시도는 402 재생이 아니라 새로 처리되어 409 INVALID_STATE)")
    void r4_4_pay_afterDecline_isNotReplayed() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();
        String key = uniqueKey();
        PG.decline();
        assertProblem(pay(orderId, key, "tok"), 402, "PAYMENT_DECLINED");

        ApiResponse retry = pay(orderId, key, "tok");

        assertProblem(retry, 409, "INVALID_STATE");
        assertThat(PG.paymentCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.4 존재하지 않는 주문 결제(404)의 키는 같은 요청으로 다시 시도 가능하다 (404가 다시 처리됨)")
    void r4_4_pay_afterNotFound_keyNotStored() {
        String key = uniqueKey();

        assertThat(pay(999_999_998L, key, "tok").status()).isEqualTo(404);
        ApiResponse again = pay(999_999_998L, key, "tok");

        assertProblem(again, 404, "ORDER_NOT_FOUND");
    }

    // ------------------------------------------------------------------ R4.5

    @Test
    @DisplayName("R4.5 같은 키로 주문 생성이 동시에 오면 실제 처리는 한 번이고 나머지는 같은 201 재생이거나 409 IDEMPOTENCY_IN_PROGRESS이다")
    void r4_5_create_concurrentSameKey_processedOnce() {
        long productId = newProduct(1_000, 100);
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderJson(null, line(productId, 1));

        List<ApiResponse> results = runConcurrently(12, i -> postOrder(user, key, body));

        List<ApiResponse> created = results.stream().filter(r -> r.status() == 201).toList();
        List<ApiResponse> others = results.stream().filter(r -> r.status() != 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(created).extracting(ApiResponse::body).containsOnly(created.get(0).body());
        assertThat(others).allSatisfy(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(reserved(productId)).isEqualTo(1);
        assertThat(listOrders("userId=" + user).json().get("content")).hasSize(1);
    }

    @Test
    @DisplayName("R4.5 같은 키로 결제가 동시에 오면 PG 결제 요청은 한 번이고 나머지는 같은 200 재생이거나 409 IDEMPOTENCY_IN_PROGRESS이다")
    void r4_5_pay_concurrentSameKey_processedOnce() {
        long productId = newProduct(1_000, 10);
        long orderId = placeOrderOk(productId, 2).id();
        String key = uniqueKey();
        PG.delayPayment(300);

        List<ApiResponse> results = runConcurrently(8, i -> pay(orderId, key, "tok"));

        List<ApiResponse> ok = results.stream().filter(r -> r.status() == 200).toList();
        List<ApiResponse> others = results.stream().filter(r -> r.status() != 200).toList();
        assertThat(ok).isNotEmpty();
        assertThat(ok).extracting(ApiResponse::body).containsOnly(ok.get(0).body());
        assertThat(others).allSatisfy(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(PG.paymentCallCount()).isEqualTo(1);
        assertThat(stock(productId)).isEqualTo(8);
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R4.5 처리 중인 키로 같은 요청이 오면 409 IDEMPOTENCY_IN_PROGRESS, 다른 요청이면 422이고, 완료 후에는 최초 응답이 재생된다")
    void r4_5_pay_inProgress_conflictsThenReplaysAfterCompletion() {
        long orderId = placeOrderOk(newProduct(1_000, 10), 1).id();
        String key = uniqueKey();
        PG.delayPayment(1_500);
        CompletableFuture<ApiResponse> first = CompletableFuture.supplyAsync(() -> pay(orderId, key, "tok"));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> PG.paymentCallCount() == 1);

        ApiResponse sameRequest = pay(orderId, key, "tok");
        ApiResponse differentRequest = pay(orderId, key, "other-token");
        ApiResponse firstResult = first.join();
        ApiResponse replay = pay(orderId, key, "tok");

        assertProblem(sameRequest, 409, "IDEMPOTENCY_IN_PROGRESS");
        assertProblem(differentRequest, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(firstResult.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(firstResult.body());
        assertThat(PG.paymentCallCount()).isEqualTo(1);
    }
}
