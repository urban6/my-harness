package com.example.order;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R4 멱등성")
class R04IdempotencyTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------- R4.1 required / independent spaces

    @Test
    @DisplayName("R4.1 결제에도 Idempotency-Key 가 필수 -> 누락 400, PG 미호출")
    void pay_missingKey_400() {
        long p = newProduct(1_000, 5);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("APPROVED", "pay-x");

        ApiResponse r = post("/api/orders/" + order.id() + "/pay", obj().put("cardToken", "tok"), null);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(pgPaymentRequestCount()).isZero();
        assertThat(statusOf(order.id())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.1 주문 생성 키와 결제 키는 독립 -> 같은 문자열 키를 둘 다 쓸 수 있다")
    void keySpaces_areIndependent() {
        long p = newProduct(1_000, 5);
        String key = uniqueKey();
        ApiResponse order = createOrder(uniqueUser(), key, null, p, 1);
        stubPgPayment("APPROVED", "pay-same-key");

        ApiResponse paid = pay(order.id(), key, "tok");

        assertThat(order.status()).isEqualTo(201);
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.json("status").asText()).isEqualTo("PAID");
        // 각자의 키로 재생
        assertThat(createOrder(order.json("userId").asText(), key, null, p, 1).status()).isEqualTo(201);
        assertThat(pay(order.id(), key, "tok").status()).isEqualTo(200);
    }

    // ------------------------------------------------------------- R4.2 replay

    @Test
    @DisplayName("R4.2 주문 생성 재생 -> 같은 상태코드·본문·Location, 주문/예약은 1회만")
    void createOrder_replay_sameResponse_processedOnce() {
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        ApiResponse first = createOrder(user, key, null, p, 2);

        ApiResponse replay = createOrder(user, key, null, p, 2);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.header("Location")).isEqualTo(first.header("Location"));
        assertThat(reservedOf(p)).isEqualTo(2);
        assertThat(listOrders("userId=" + user).json("content")).hasSize(1);
    }

    @Test
    @DisplayName("R4.2 재생 시 재고가 소진돼도(재처리라면 409) 최초 응답을 돌려준다")
    void createOrder_replay_ignoresCurrentStock() {
        long p = newProduct(1_000, 1);
        String user = uniqueUser();
        String key = uniqueKey();
        ApiResponse first = createOrder(user, key, null, p, 1);
        assertThat(first.status()).isEqualTo(201);

        ApiResponse replay = createOrder(user, key, null, p, 1);

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
    }

    @Test
    @DisplayName("R4.2 본문의 필드 순서/공백만 다른 같은 요청은 재생")
    void createOrder_replay_withReorderedJson() {
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        ApiResponse first = post("/api/orders",
                "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}],\"couponCode\":null}",
                headers("X-User-Id", user, "Idempotency-Key", key));

        ApiResponse replay = post("/api/orders",
                "{ \"couponCode\" : null,\n \"items\": [ { \"quantity\": 1, \"productId\": " + p + " } ] }",
                headers("X-User-Id", user, "Idempotency-Key", key));

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(reservedOf(p)).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.2 결제 재생 -> 같은 상태코드·본문, PG 는 1번만 호출")
    void pay_replay_sameResponse_pgCalledOnce() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 2);
        stubPgPayment("APPROVED", "pay-replay");
        String key = uniqueKey();
        ApiResponse first = pay(order.id(), key, "tok");

        ApiResponse replay = pay(order.id(), key, "tok");

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(pgPaymentRequestCount()).isEqualTo(1);
        assertThat(stockOf(p)).isEqualTo(8);
        assertThat(reservedOf(p)).isZero();
    }

    @Test
    @DisplayName("R4.2 결제 재생 -> 주문이 이후 상태로 바뀌었어도(SHIPPED) 최초 응답을 그대로 재생")
    void pay_replay_afterOrderMovedOn() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("APPROVED", "pay-replay2");
        String key = uniqueKey();
        ApiResponse first = pay(order.id(), key, "tok");
        ship(order.id());

        ApiResponse replay = pay(order.id(), key, "tok");

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.json("status").asText()).isEqualTo("PAID");
    }

    // ------------------------------------------------------------- R4.3 mismatch

    @Test
    @DisplayName("R4.3 같은 키, 다른 본문(수량) -> 422 IDEMPOTENCY_KEY_MISMATCH, 새 주문 없음")
    void createOrder_differentBody_422() {
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        createOrder(user, key, null, p, 1);

        ApiResponse r = createOrder(user, key, null, p, 2);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(r.isProblemJson()).isTrue();
        assertThat(reservedOf(p)).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 같은 키, 다른 상품 -> 422")
    void createOrder_differentProduct_422() {
        long p = newProduct(1_000, 10);
        long q = newProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        createOrder(user, key, null, p, 1);

        assertThat(createOrder(user, key, null, q, 1).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("R4.3 같은 키, 다른 couponCode -> 422")
    void createOrder_differentCoupon_422() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 10, null, null, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        createOrder(user, key, null, p, 1);

        assertThat(createOrder(user, key, coupon, p, 1).status()).isEqualTo(422);
        assertThat(usedCountOf(coupon)).isZero();
    }

    @Test
    @DisplayName("R4.3 같은 키, 다른 X-User-Id -> 422")
    void createOrder_differentUser_422() {
        long p = newProduct(1_000, 10);
        String key = uniqueKey();
        createOrder(uniqueUser(), key, null, p, 1);

        ApiResponse r = createOrder(uniqueUser(), key, null, p, 1);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(reservedOf(p)).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 결제: 같은 키, 다른 cardToken -> 422, PG 재호출 없음")
    void pay_differentCardToken_422() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("APPROVED", "pay-mm");
        String key = uniqueKey();
        pay(order.id(), key, "tok-A");

        ApiResponse r = pay(order.id(), key, "tok-B");

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(pgPaymentRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 결제: 같은 키, 다른 주문(경로) -> 422 (404 보다 우선)")
    void pay_differentOrder_422_beats404() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        ApiResponse other = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("APPROVED", "pay-mm2");
        String key = uniqueKey();
        pay(order.id(), key, "tok");

        assertThat(pay(other.id(), key, "tok").status()).isEqualTo(422);
        assertThat(pay(Long.MAX_VALUE - 1, key, "tok").status()).isEqualTo(422);
        assertThat(statusOf(other.id())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.3 결제: 같은 키, 다른 X-User-Id -> 422, 같은 X-User-Id 면 재생")
    void pay_differentUserId_422() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("APPROVED", "pay-mm3");
        String key = uniqueKey();
        ApiResponse first = payAs(order.id(), key, "tok", "alice");

        ApiResponse replay = payAs(order.id(), key, "tok", "alice");
        ApiResponse other = payAs(order.id(), key, "tok", "bob");

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(other.status()).isEqualTo(422);
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 400 이 422 보다 우선: 기존 키 + 검증 오류 본문 -> 400")
    void validationError_beatsMismatch() {
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        createOrder(user, key, null, p, 1);

        ApiResponse r = createOrder(user, key, null, p, 0);

        assertThat(r.status()).isEqualTo(400);
    }

    // ------------------------------------------------------------- R4.4 only 2xx stored

    @Test
    @DisplayName("R4.4 404 로 끝난 키는 같은 요청으로 재시도할 수 있다 (쿠폰을 만든 뒤 재시도 -> 201)")
    void createOrder_after404_sameRequestRetriable() {
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        String code = uniqueCode();
        ApiResponse failed = createOrder(user, key, code, p, 1);
        assertThat(failed.status()).isEqualTo(404);
        assertThat(createCoupon(code, "FIXED", 100, 5).status()).isEqualTo(201);

        ApiResponse retry = createOrder(user, key, code, p, 1);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(reservedOf(p)).isEqualTo(1);
        assertThat(usedCountOf(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.4 409 INSUFFICIENT_STOCK 로 끝난 키도 재고 보충 후 재시도하면 201")
    void createOrder_after409_sameRequestRetriable() {
        long p = newProduct(1_000, 1);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(createOrder(user, key, null, p, 3).status()).isEqualTo(409);
        jdbc.update("update products set stock = 10 where id = ?", p);

        ApiResponse retry = createOrder(user, key, null, p, 3);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(reservedOf(p)).isEqualTo(3);
    }

    @Test
    @DisplayName("R4.4 오류로 끝난 키가 다른 요청에는 422 를 만들지 않는다 (오류 응답은 저장되지 않음)")
    void createOrder_afterError_differentRequestWithSameKeyIsProcessed() {
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(createOrder(user, key, null, Long.MAX_VALUE - 1, 1).status()).isEqualTo(404);

        ApiResponse r = createOrder(user, key, null, p, 1);

        assertThat(r.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R4.4 400 검증 오류도 키를 소비하지 않는다")
    void createOrder_after400_keyNotConsumed() {
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(createOrder(user, key, null, p, 0).status()).isEqualTo(400);

        assertThat(createOrder(user, key, null, p, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R4.4 결제 503 후 같은 키/같은 요청 재시도 -> 200, PG 는 클라이언트 키로 호출")
    void pay_after503_sameRequestRetriable() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        String key = uniqueKey();
        stubPgPaymentStatus(503);
        assertThat(pay(order.id(), key, "tok").status()).isEqualTo(503);
        WIREMOCK.resetAll();
        stubPgPayment("APPROVED", "pay-retry");

        ApiResponse retry = pay(order.id(), key, "tok");

        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json("status").asText()).isEqualTo("PAID");
        WIREMOCK.verify(exactly(1), postRequestedFor(urlEqualTo("/v1/payments"))
                .withHeader("Idempotency-Key", com.github.tomakehurst.wiremock.client.WireMock.equalTo(key)));
    }

    @Test
    @DisplayName("R4.4 결제 402 는 저장되지 않는다: 같은 키 재요청은 402 재생이 아니라 새로 처리되어 409 INVALID_STATE")
    void pay_402_notStored() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("DECLINED", "pay-declined-idem");
        String key = uniqueKey();
        assertThat(pay(order.id(), key, "tok").status()).isEqualTo(402);

        ApiResponse again = pay(order.id(), key, "tok");

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("INVALID_STATE");
        assertThat(pgPaymentRequestCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------- R4.5 concurrency

    @Test
    @DisplayName("R4.5 같은 키 주문 생성 동시 요청 -> 처리 1회, 나머지는 재생(201 동일 본문) 또는 409 IDEMPOTENCY_IN_PROGRESS")
    void createOrder_sameKeyConcurrent_processedOnce() {
        long p = newProduct(1_000, 50);
        String user = uniqueUser();
        String key = uniqueKey();
        List<Callable<ApiResponse>> tasks = IntStream.range(0, 10)
                .<Callable<ApiResponse>>mapToObj(i -> () -> createOrder(user, key, null, p, 2)).toList();

        List<ApiResponse> rs = runConcurrently(tasks);

        assertThat(rs).allSatisfy(r -> {
            assertThat(r.status()).isIn(201, 409);
            if (r.status() == 409) {
                assertThat(r.code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
            }
        });
        List<ApiResponse> created = rs.stream().filter(r -> r.status() == 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(created).allSatisfy(r -> assertThat(r.body()).isEqualTo(created.get(0).body()));
        assertThat(reservedOf(p)).isEqualTo(2);
        assertThat(listOrders("userId=" + user).json("content")).hasSize(1);
    }

    @Test
    @DisplayName("R4.5 처리 중인 같은 키의 결제 요청 -> 409 IDEMPOTENCY_IN_PROGRESS, 선행 요청은 200, PG 는 1회")
    void pay_sameKeyWhileInProgress_409_thenReplay() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPaymentDelayed("APPROVED", "pay-slow", 1_500);
        String key = uniqueKey();
        CompletableFuture<ApiResponse> first = CompletableFuture.supplyAsync(() -> pay(order.id(), key, "tok"));
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .until(() -> pgPaymentRequestCount() >= 1);

        ApiResponse during = pay(order.id(), key, "tok");
        ApiResponse firstResult = first.join();
        ApiResponse after = pay(order.id(), key, "tok");

        assertThat(during.status()).isEqualTo(409);
        assertThat(during.code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
        assertThat(firstResult.status()).isEqualTo(200);
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body()).isEqualTo(firstResult.body());
        assertThat(pgPaymentRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.5 같은 키 결제 동시 요청 -> PG 1회, 200 은 모두 동일 본문, 나머지는 409 IDEMPOTENCY_IN_PROGRESS")
    void pay_sameKeyConcurrent_processedOnce() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPaymentDelayed("APPROVED", "pay-conc", 300);
        String key = uniqueKey();
        List<Callable<ApiResponse>> tasks = IntStream.range(0, 8)
                .<Callable<ApiResponse>>mapToObj(i -> () -> pay(order.id(), key, "tok")).toList();

        List<ApiResponse> rs = runConcurrently(tasks);

        assertThat(rs).allSatisfy(r -> {
            assertThat(r.status()).isIn(200, 409);
            if (r.status() == 409) {
                assertThat(r.code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
            }
        });
        List<ApiResponse> ok = rs.stream().filter(r -> r.status() == 200).toList();
        assertThat(ok).isNotEmpty();
        assertThat(ok).allSatisfy(r -> assertThat(r.body()).isEqualTo(ok.get(0).body()));
        WIREMOCK.verify(exactly(1), postRequestedFor(urlEqualTo("/v1/payments")));
        assertThat(stockOf(p)).isEqualTo(9);
    }
}
