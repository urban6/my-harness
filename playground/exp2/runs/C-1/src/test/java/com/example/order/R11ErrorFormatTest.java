package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** assertProblem 이 Content-Type(application/problem+json)과 type/title/status/detail/code 필드를 함께 검증한다. */
@DisplayName("R11 에러 포맷 (RFC 9457 problem+json)")
class R11ErrorFormatTest extends IntegrationTestBase {

    private long product(int stock) {
        return createProduct("상품", 1000, stock).get("id").asLong();
    }

    // ---- 400 VALIDATION_ERROR ----

    @Test
    @DisplayName("R11 400: 상품 본문 검증 실패")
    void code400_productValidation() {
        assertProblem(post("/api/products", Map.of("name", "", "price", 0, "stock", -1)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11 400: 쿠폰 본문 검증 실패")
    void code400_couponValidation() {
        assertProblem(post("/api/coupons", Map.of("code", "x")), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11 400: 헤더 누락/위반")
    void code400_headers() {
        long p = product(5);
        assertProblem(post("/api/orders", orderBody(null, p, 1), "Idempotency-Key", key()), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders", orderBody(null, p, 1), "X-User-Id", "u"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11 400: 쿼리 검증 실패(size, status, cursor)")
    void code400_query() {
        assertProblem(get("/api/orders?size=0"), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders?status=NOPE"), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders?cursor=garbage!!"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11 400: 요청 본문 JSON 파싱 실패 (상품·쿠폰·주문·결제)")
    void code400_jsonParseFailure() {
        long p = product(5);
        long id = orderOk("u1", null, p, 1).get("id").asLong();

        assertProblem(post("/api/products", "{\"name\": "), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/coupons", "not json at all"), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders", "{\"items\": [{", "X-User-Id", "u", "Idempotency-Key", key()), 400,
                "VALIDATION_ERROR");
        assertProblem(post("/api/orders/" + id + "/pay", "{cardToken: tok}", "Idempotency-Key", key()), 400,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11 400: 본문 자체가 없는 요청")
    void code400_missingBody() {
        long p = product(5);
        assertProblem(post("/api/products", null), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders", null, "X-User-Id", "u", "Idempotency-Key", key()), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11 400: 타입이 틀린 필드 값")
    void code400_wrongTypes() {
        assertProblem(post("/api/products", Map.of("name", "n", "price", "비싸다", "stock", 1)), 400,
                "VALIDATION_ERROR");
        assertProblem(post("/api/coupons", Map.of("code", "ABCD", "type", "WRONG", "value", 1, "totalQuantity", 1,
                "validFrom", "2030-01-01T00:00:00Z", "validUntil", "2031-01-01T00:00:00Z")), 400,
                "VALIDATION_ERROR");
    }

    // ---- 402 ----

    @Test
    @DisplayName("R11 402 PAYMENT_DECLINED")
    void code402() {
        long id = orderOk("u1", null, product(5), 1).get("id").asLong();
        PG.decline();

        assertProblem(payOrder(id, key(), "tok"), 402, "PAYMENT_DECLINED");
    }

    // ---- 404 ----

    @Test
    @DisplayName("R11 404 PRODUCT_NOT_FOUND / COUPON_NOT_FOUND / ORDER_NOT_FOUND")
    void code404() {
        long p = product(5);

        assertProblem(get("/api/products/9999"), 404, "PRODUCT_NOT_FOUND");
        assertProblem(order("u1", null, 9999, 1), 404, "PRODUCT_NOT_FOUND");
        assertProblem(get("/api/coupons/NOPE0001"), 404, "COUPON_NOT_FOUND");
        assertProblem(order("u1", "NOPE0001", p, 1), 404, "COUPON_NOT_FOUND");
        assertProblem(get("/api/orders/9999"), 404, "ORDER_NOT_FOUND");
        assertProblem(payOrder(9999, key(), "tok"), 404, "ORDER_NOT_FOUND");
        assertProblem(cancel(9999), 404, "ORDER_NOT_FOUND");
        assertProblem(ship(9999), 404, "ORDER_NOT_FOUND");
        assertProblem(deliver(9999), 404, "ORDER_NOT_FOUND");
    }

    // ---- 409 ----

    @Test
    @DisplayName("R11 409 INSUFFICIENT_STOCK")
    void code409_insufficientStock() {
        assertProblem(order("u1", null, product(1), 2), 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("R11 409 COUPON_NOT_APPLICABLE / COUPON_EXHAUSTED")
    void code409_coupon() {
        long p = product(10);
        Map<String, Object> c = couponBody("ONEUSE01", "FIXED", 100);
        c.put("totalQuantity", 1);
        createCoupon(c);
        orderOk("u1", "ONEUSE01", p, 1);

        assertProblem(order("u1", "ONEUSE01", p, 1), 409, "COUPON_NOT_APPLICABLE");
        assertProblem(order("u2", "ONEUSE01", p, 1), 409, "COUPON_EXHAUSTED");
    }

    @Test
    @DisplayName("R11 409 DUPLICATE_COUPON_CODE")
    void code409_duplicateCoupon() {
        createCoupon("DUPLIC01", "FIXED", 100);

        assertProblem(post("/api/coupons", couponBody("DUPLIC01", "FIXED", 100)), 409, "DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("R11 409 INVALID_STATE")
    void code409_invalidState() {
        long id = orderOk("u1", null, product(5), 1).get("id").asLong();

        assertProblem(ship(id), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R11 409 IDEMPOTENCY_IN_PROGRESS (같은 키 결제가 PG 응답을 기다리는 동안 같은 키로 재요청)")
    void code409_idempotencyInProgress() throws Exception {
        long id = orderOk("u1", null, product(5), 1).get("id").asLong();
        PG.delay(1500);
        CompletableFuture<ResponseEntity<JsonNode>> inFlight = CompletableFuture.supplyAsync(
                () -> payOrder(id, "inflight-key", "tok"));
        assertThat(awaitUntil(Instant.now().plusSeconds(5), () -> PG.payCallCount() >= 1))
                .as("첫 결제 요청이 PG 에 도달").isTrue();

        ResponseEntity<JsonNode> second = payOrder(id, "inflight-key", "tok");

        assertProblem(second, 409, "IDEMPOTENCY_IN_PROGRESS");
        ResponseEntity<JsonNode> first = inFlight.get();
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(PG.payCallCount()).isEqualTo(1);
    }

    // ---- 422 ----

    @Test
    @DisplayName("R11 422 IDEMPOTENCY_KEY_MISMATCH")
    void code422() {
        long p = product(5);
        createOrder("u1", "mm-key", orderBody(null, p, 1));

        assertProblem(createOrder("u1", "mm-key", orderBody(null, p, 2)), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    // ---- 503 ----

    @Test
    @DisplayName("R11 503 PAYMENT_GATEWAY_UNAVAILABLE (결제·환불)")
    void code503() {
        long p = product(5);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        PG.fail5xx();
        assertProblem(payOrder(id, key(), "tok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        PG.approve();
        payOk(id);
        PG.fail5xx();
        assertProblem(cancel(id), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
    }

    @Test
    @DisplayName("R11 오류 본문의 status 필드는 HTTP 상태와 같고 type 은 URI 문자열, detail/title 은 비어 있지 않다")
    void fieldValuesAreSane() {
        ResponseEntity<JsonNode> res = get("/api/orders/9999");

        assertProblem(res, 404, "ORDER_NOT_FOUND");
        JsonNode b = res.getBody();
        assertThat(java.net.URI.create(b.get("type").asText()).toString()).isNotBlank();
        assertThat(b.get("title").asText()).isNotBlank();
        assertThat(b.get("detail").asText()).isNotBlank();
    }
}
