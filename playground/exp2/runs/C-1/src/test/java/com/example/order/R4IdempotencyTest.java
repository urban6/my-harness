package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R4 멱등성")
class R4IdempotencyTest extends IntegrationTestBase {

    private long product(long price, int stock) {
        return createProduct("상품", price, stock).get("id").asLong();
    }

    // ---------------- R4.1 ----------------

    @Test
    @DisplayName("R4.1 주문 생성에 Idempotency-Key 가 없으면 400")
    void r4_1_createRequiresKey() {
        long p = product(1000, 10);

        assertProblem(post("/api/orders", orderBody(null, p, 1), "X-User-Id", "u1"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R4.1 결제에 Idempotency-Key 가 없으면 400, PG 미호출")
    void r4_1_payRequiresKey() {
        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 1).get("id").asLong();

        ResponseEntity<JsonNode> res = post("/api/orders/" + id + "/pay", Map.of("cardToken", "tok"));

        assertProblem(res, 400, "VALIDATION_ERROR");
        assertThat(PG.payCallCount()).isZero();
        assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.1 주문 생성과 결제의 키 공간은 독립이다 (같은 키 문자열을 양쪽에 써도 각각 처리)")
    void r4_1_independentKeySpaces() {
        long p = product(1000, 10);
        String shared = "shared-key";
        ResponseEntity<JsonNode> created = createOrder("u1", shared, orderBody(null, p, 1));
        long id = created.getBody().get("id").asLong();

        ResponseEntity<JsonNode> paid = payOrder(id, shared, "tok");

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(paid.getStatusCode().value()).isEqualTo(200);
        assertThat(paid.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(PG.payCallCount()).isEqualTo(1);
        // 각각 재생
        assertThat(createOrder("u1", shared, orderBody(null, p, 1)).getBody().get("status").asText())
                .isEqualTo("PENDING_PAYMENT");
        assertThat(payOrder(id, shared, "tok").getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(countOrders()).isEqualTo(1);
    }

    // ---------------- R4.2 ----------------

    @Test
    @DisplayName("R4.2 주문 생성 재요청은 처리 없이 최초 응답(상태코드·본문·Location)을 그대로 돌려준다")
    void r4_2_createReplay() {
        long p = product(1000, 10);
        Map<String, Object> body = orderBody(null, p, 2);
        ResponseEntity<JsonNode> first = createOrder("u1", "k-create", body);

        ResponseEntity<JsonNode> second = createOrder("u1", "k-create", body);

        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(countOrders()).isEqualTo(1);
        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("R4.2 쿠폰 주문 재요청은 usedCount 를 다시 올리지 않는다")
    void r4_2_createReplayDoesNotReuseCoupon() {
        long p = product(1000, 10);
        createCoupon("IDEMCP01", "FIXED", 100);
        Map<String, Object> body = orderBody("IDEMCP01", p, 1);

        createOrder("u1", "k-coupon", body);
        ResponseEntity<JsonNode> replay = createOrder("u1", "k-coupon", body);

        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(getCoupon("IDEMCP01").get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.2 최초 응답 이후 주문 상태가 바뀌어도 재요청은 최초 응답(PENDING_PAYMENT)을 돌려준다")
    void r4_2_createReplayIsFirstResponseSnapshot() {
        long p = product(1000, 10);
        Map<String, Object> body = orderBody(null, p, 1);
        ResponseEntity<JsonNode> first = createOrder("u1", "k-snap", body);
        cancel(first.getBody().get("id").asLong());

        ResponseEntity<JsonNode> replay = createOrder("u1", "k-snap", body);

        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getBody().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.2 재고가 이후에 바닥나도 같은 키 재요청은 409 가 아니라 최초 201 을 재생")
    void r4_2_replayWinsOverCurrentStock() {
        long p = product(1000, 1);
        Map<String, Object> body = orderBody(null, p, 1);
        ResponseEntity<JsonNode> first = createOrder("u1", "k-stock", body);

        ResponseEntity<JsonNode> replay = createOrder("u1", "k-stock", body);

        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
    }

    @Test
    @DisplayName("R4.2 결제 재요청은 PG 를 다시 호출하지 않고 최초 200 응답을 돌려준다")
    void r4_2_payReplay() {
        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 2).get("id").asLong();
        ResponseEntity<JsonNode> first = payOrder(id, "k-pay", "tok");

        ResponseEntity<JsonNode> second = payOrder(id, "k-pay", "tok");

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(PG.payCallCount()).isEqualTo(1);
        JsonNode prod = getProduct(p);
        assertThat(prod.get("stock").asInt()).isEqualTo(8);
        assertThat(prod.get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R4.2 결제 후 배송 상태로 바뀌어도 같은 키 재요청은 최초 응답(PAID)을 돌려준다")
    void r4_2_payReplayAfterShip() {
        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        ResponseEntity<JsonNode> first = payOrder(id, "k-pay2", "tok");
        ship(id);

        ResponseEntity<JsonNode> replay = payOrder(id, "k-pay2", "tok");

        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getBody().get("status").asText()).isEqualTo("PAID");
    }

    // ---------------- R4.3 ----------------

    @Test
    @DisplayName("R4.3 같은 키로 본문(수량)이 다른 주문 생성 -> 422 IDEMPOTENCY_KEY_MISMATCH, 추가 예약 없음")
    void r4_3_createDifferentBody() {
        long p = product(1000, 10);
        createOrder("u1", "k-mm", orderBody(null, p, 1));

        ResponseEntity<JsonNode> res = createOrder("u1", "k-mm", orderBody(null, p, 2));

        assertProblem(res, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(1);
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 다른 상품 / 다른 쿠폰 코드 -> 422")
    void r4_3_createDifferentItemsOrCoupon() {
        long p1 = product(1000, 10);
        long p2 = product(1000, 10);
        createCoupon("MISMATCH", "FIXED", 100);
        createOrder("u1", "k-mm2", orderBody(null, p1, 1));

        assertProblem(createOrder("u1", "k-mm2", orderBody(null, p2, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblem(createOrder("u1", "k-mm2", orderBody("MISMATCH", p1, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R4.3 같은 키로 X-User-Id 가 다른 주문 생성 -> 422")
    void r4_3_createDifferentUser() {
        long p = product(1000, 10);
        Map<String, Object> body = orderBody(null, p, 1);
        createOrder("u1", "k-mm3", body);

        ResponseEntity<JsonNode> res = createOrder("u2", "k-mm3", body);

        assertProblem(res, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 cardToken 이 다른 결제 -> 422, PG 재호출 없음")
    void r4_3_payDifferentCardToken() {
        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        payOrder(id, "k-pmm", "tok_a");

        ResponseEntity<JsonNode> res = payOrder(id, "k-pmm", "tok_b");

        assertProblem(res, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(PG.payCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 같은 키로 다른 주문(경로)에 결제 -> 422, 두 번째 주문은 PENDING_PAYMENT 유지")
    void r4_3_payDifferentOrder() {
        long p = product(1000, 10);
        long id1 = orderOk("u1", null, p, 1).get("id").asLong();
        long id2 = orderOk("u2", null, p, 1).get("id").asLong();
        payOrder(id1, "k-pmm2", "tok");

        ResponseEntity<JsonNode> res = payOrder(id2, "k-pmm2", "tok");

        assertProblem(res, 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(statusOf(id2)).isEqualTo("PENDING_PAYMENT");
        assertThat(PG.payCallCount()).isEqualTo(1);
    }

    // ---------------- R4.4 ----------------

    @Test
    @DisplayName("R4.4 409 로 끝난 주문 생성의 키는 같은 요청으로 다시 시도할 수 있다 (오류 응답은 재생되지 않는다)")
    void r4_4_createRetryAfter409() {
        long p = product(1000, 1);
        long blocker = orderOk("u1", null, p, 1).get("id").asLong();
        Map<String, Object> body = orderBody(null, p, 1);
        assertProblem(createOrder("u2", "k-retry", body), 409, "INSUFFICIENT_STOCK");

        cancel(blocker);
        ResponseEntity<JsonNode> retry = createOrder("u2", "k-retry", body);

        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getBody().get("userId").asText()).isEqualTo("u2");
    }

    @Test
    @DisplayName("R4.4 404 로 끝난 주문 생성의 키는 같은 요청으로 다시 시도할 수 있다")
    void r4_4_createRetryAfter404() {
        createCoupon("LATECOUP", "FIXED", 100);
        long p = product(1000, 10);
        Map<String, Object> body = orderBody("NOCOUPON", p, 1);
        assertProblem(createOrder("u1", "k-retry2", body), 404, "COUPON_NOT_FOUND");

        // 같은 키 + 같은 요청: 여전히 404 (재생이 아니라 다시 평가). 이후 쿠폰을 만들면 성공.
        assertProblem(createOrder("u1", "k-retry2", body), 404, "COUPON_NOT_FOUND");
        createCoupon("NOCOUPON", "FIXED", 100);
        assertThat(createOrder("u1", "k-retry2", body).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("R4.4 400 으로 끝난 요청은 키를 소모하지 않는다")
    void r4_4_validationErrorDoesNotConsumeKey() {
        long p = product(1000, 10);
        assertProblem(createOrder("u1", "k-400", orderBody(null, p, 0)), 400, "VALIDATION_ERROR");

        // 다른 본문으로 같은 키를 써도 422 가 아니라 정상 처리
        assertThat(createOrder("u1", "k-400", orderBody(null, p, 1)).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("R4.4 결제 거절(402)은 저장되지 않는다 - 같은 키 재시도는 재생이 아니라 다시 평가되어 409 INVALID_STATE")
    void r4_4_declinedNotReplayed() {
        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        PG.decline();
        assertProblem(payOrder(id, "k-dec", "tok"), 402, "PAYMENT_DECLINED");

        ResponseEntity<JsonNode> retry = payOrder(id, "k-dec", "tok");

        assertProblem(retry, 409, "INVALID_STATE");
        assertThat(PG.payCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.4 PG 장애(503)로 끝난 결제의 키는 같은 요청으로 재시도할 수 있고, PG 에는 같은 Idempotency-Key 가 전달된다")
    void r4_4_payRetryAfter503() {
        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        PG.fail5xx();
        assertProblem(payOrder(id, "k-503", "tok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        PG.approve();
        ResponseEntity<JsonNode> retry = payOrder(id, "k-503", "tok");

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(retry.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(PG.payCallCount()).isEqualTo(2);
        assertThat(PG.requests()).allSatisfy(r -> assertThat(r.idempotencyKey()).isEqualTo("k-503"));
    }

    @Test
    @DisplayName("R4.4 존재하지 않는 주문 결제(404)의 키는 소모되지 않는다")
    void r4_4_payNotFoundDoesNotConsumeKey() {
        assertProblem(payOrder(9999, "k-404", "tok"), 404, "ORDER_NOT_FOUND");

        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        // 같은 키, 다른 경로여도 첫 시도가 저장되지 않았으므로 422 가 아니다
        assertThat(payOrder(id, "k-404", "tok").getStatusCode().value()).isEqualTo(200);
    }

    // ---------------- R4.5 ----------------

    @Test
    @DisplayName("R4.5 같은 키·같은 요청의 주문 생성 12건이 동시에 오면 주문은 1건만 만들어지고 나머지는 재생 또는 409 IN_PROGRESS")
    void r4_5_concurrentCreateSameKey() {
        long p = product(1000, 100);
        Map<String, Object> body = orderBody(null, p, 3);
        List<Supplier<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(() -> createOrder("u1", "k-conc", body));
        }

        List<ResponseEntity<JsonNode>> results = runConcurrently(tasks);

        long created = countStatus(results, 201);
        long inProgress = results.stream()
                .filter(r -> r.getStatusCode().value() == 409)
                .peek(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"))
                .count();
        assertThat(created).isGreaterThanOrEqualTo(1);
        assertThat(created + inProgress).as("201 과 409(IN_PROGRESS) 외의 응답이 없어야 한다").isEqualTo(12);
        Set<Long> ids = results.stream().filter(r -> r.getStatusCode().value() == 201)
                .map(r -> r.getBody().get("id").asLong()).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(countOrders()).isEqualTo(1);
        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("R4.5 같은 키·같은 요청의 결제 8건이 동시에 오면 PG 결제는 1번, 재고 차감도 1번")
    void r4_5_concurrentPaySameKey() {
        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 2).get("id").asLong();
        List<Supplier<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> payOrder(id, "k-conc-pay", "tok"));
        }

        List<ResponseEntity<JsonNode>> results = runConcurrently(tasks);

        long ok = countStatus(results, 200);
        long inProgress = results.stream()
                .filter(r -> r.getStatusCode().value() == 409)
                .peek(r -> assertProblem(r, 409, "IDEMPOTENCY_IN_PROGRESS"))
                .count();
        assertThat(ok).isGreaterThanOrEqualTo(1);
        assertThat(ok + inProgress).isEqualTo(8);
        assertThat(PG.payCallCount()).isEqualTo(1);
        JsonNode prod = getProduct(p);
        assertThat(prod.get("stock").asInt()).isEqualTo(8);
        assertThat(prod.get("reserved").asInt()).isZero();
        results.stream().filter(r -> r.getStatusCode().value() == 200)
                .forEach(r -> assertThat(r.getBody().get("status").asText()).isEqualTo("PAID"));
    }

    @Test
    @DisplayName("R4.5 동시 요청이 끝난 뒤 같은 키 재요청은 최초 응답 재생")
    void r4_5_afterConcurrentRunReplayWorks() {
        long p = product(1000, 100);
        Map<String, Object> body = orderBody(null, p, 1);
        List<Supplier<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> createOrder("u1", "k-conc2", body));
        }
        List<ResponseEntity<JsonNode>> results = runConcurrently(tasks);
        JsonNode original = results.stream().filter(r -> r.getStatusCode().value() == 201).findFirst()
                .orElseThrow().getBody();

        ResponseEntity<JsonNode> replay = createOrder("u1", "k-conc2", body);

        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getBody()).isEqualTo(original);
        assertThat(countOrders()).isEqualTo(1);
    }
}
