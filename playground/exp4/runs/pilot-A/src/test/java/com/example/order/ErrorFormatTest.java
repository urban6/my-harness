package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R11. 에러 포맷 (RFC 9457 Problem Details) */
class ErrorFormatTest extends IntegrationTestBase {

    private void assertProblem(Res r, int status, String code) {
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.header("Content-Type")).startsWith("application/problem+json");
        JsonNode body = r.json();
        for (String field : new String[]{"type", "title", "status", "detail", "code"}) {
            assertThat(body.hasNonNull(field)).as("field %s in %s", field, r.raw()).isTrue();
        }
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("detail").asText()).isNotBlank();
    }

    @Test
    @DisplayName("R11.3 400 VALIDATION_ERROR: 본문·헤더·쿼리 검증과 JSON 파싱 실패")
    void validationErrors() {
        long p = product(1000, 10);
        assertProblem(post("/api/products", Map.of("name", "", "price", 1, "stock", 1)), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/products", "{broken"), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/products", ""), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/coupons", "not json"), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders", Map.of("items", items(p, 1))), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders", "{", "X-User-Id", "u", "Idempotency-Key", "k"), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/1/pay", "{", "Idempotency-Key", "k"), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/1/pay", Map.of("cardToken", "x")), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders?size=0"), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders?status=NOPE"), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders?cursor=%25%25"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 404 PRODUCT_NOT_FOUND · COUPON_NOT_FOUND · ORDER_NOT_FOUND")
    void notFoundErrors() {
        long p = product(1000, 10);
        assertProblem(get("/api/products/987654321"), 404, "PRODUCT_NOT_FOUND");
        assertProblem(get("/api/coupons/NOSUCH"), 404, "COUPON_NOT_FOUND");
        assertProblem(get("/api/orders/987654321"), 404, "ORDER_NOT_FOUND");
        assertProblem(createOrder("u", "k-" + uniq(), items(987654321L, 1), null), 404, "PRODUCT_NOT_FOUND");
        assertProblem(createOrder("u", "k-" + uniq(), items(p, 1), "NOSUCH"), 404, "COUPON_NOT_FOUND");
        assertProblem(pay(987654321L), 404, "ORDER_NOT_FOUND");
        assertProblem(post("/api/orders/987654321/cancel", null), 404, "ORDER_NOT_FOUND");
        assertProblem(post("/api/orders/987654321/ship", null), 404, "ORDER_NOT_FOUND");
        assertProblem(post("/api/orders/987654321/deliver", null), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 409 INSUFFICIENT_STOCK · COUPON_NOT_APPLICABLE · COUPON_EXHAUSTED · DUPLICATE_COUPON_CODE · INVALID_STATE")
    void conflictErrors() {
        long p = product(1000, 1);
        assertProblem(createOrder("u", "k-" + uniq(), items(p, 2), null), 409, "INSUFFICIENT_STOCK");

        String small = coupon("FIXED", 100, 100_000, null, 1);
        assertProblem(createOrder("u", "k-" + uniq(), items(p, 1), small), 409, "COUPON_NOT_APPLICABLE");

        String once = coupon("FIXED", 100, 0, null, 1);
        long big = product(1000, 10);
        assertThat(createOrder("u1", "k-" + uniq(), items(big, 1), once).status()).isEqualTo(201);
        assertProblem(createOrder("u2", "k-" + uniq(), items(big, 1), once), 409, "COUPON_EXHAUSTED");

        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("code", once);
        dup.put("type", "FIXED");
        dup.put("value", 1);
        dup.put("totalQuantity", 1);
        dup.put("validFrom", "2026-01-01T00:00:00Z");
        dup.put("validUntil", "2099-01-01T00:00:00Z");
        assertProblem(post("/api/coupons", dup), 409, "DUPLICATE_COUPON_CODE");

        long pending = order(big, 1).get("id").asLong();
        assertProblem(post("/api/orders/" + pending + "/ship", null), 409, "INVALID_STATE");
        assertProblem(post("/api/orders/" + pending + "/deliver", null), 409, "INVALID_STATE");
        post("/api/orders/" + pending + "/cancel", null);
        assertProblem(post("/api/orders/" + pending + "/cancel", null), 409, "INVALID_STATE");
        assertProblem(pay(pending), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R11.3 409 IDEMPOTENCY_IN_PROGRESS · 422 IDEMPOTENCY_KEY_MISMATCH")
    void idempotencyErrors() throws Exception {
        long p = product(1000, 10);
        long id = order(p, 1).get("id").asLong();
        String key = "pay-" + uniq();

        // 처리 중인 키: PG 응답을 늦춰 두고 같은 키로 한 번 더 요청한다.
        GATEWAY.delay(1000);
        Thread first = new Thread(() -> pay(id, "tok_ok", key));
        first.start();
        Res inProgress = null;
        for (int i = 0; i < 40 && (inProgress == null || inProgress.status() != 409); i++) {
            Thread.sleep(25);
            inProgress = pay(id, "tok_ok", key);
        }
        first.join();
        assertThat(inProgress).isNotNull();
        assertProblem(inProgress, 409, "IDEMPOTENCY_IN_PROGRESS");

        assertProblem(pay(id, "tok_other", key), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R11.3 402 PAYMENT_DECLINED · 503 PAYMENT_GATEWAY_UNAVAILABLE")
    void paymentErrors() {
        long p = product(1000, 10);
        long declined = order(p, 1).get("id").asLong();
        assertProblem(pay(declined, FakeGateway.DECLINE_TOKEN, "pay-" + uniq()), 402, "PAYMENT_DECLINED");

        long down = order(p, 1).get("id").asLong();
        GATEWAY.mode(FakeGateway.Mode.SERVER_ERROR);
        assertProblem(pay(down), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        GATEWAY.mode(FakeGateway.Mode.NORMAL);
        long paid = paidOrder(p, 1).get("id").asLong();
        GATEWAY.mode(FakeGateway.Mode.SERVER_ERROR);
        assertProblem(post("/api/orders/" + paid + "/cancel", null), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
    }

    @Test
    @DisplayName("R11.1 Accept가 application/json이어도 오류는 application/problem+json")
    void problemJsonRegardlessOfAccept() {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/api/products/987654321"))
                .header("Accept", "application/json").GET().build();
        try {
            var response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("성공 응답은 application/json")
    void successIsJson() {
        long p = product(1000, 10);
        assertThat(get("/api/products/" + p).header("Content-Type")).startsWith("application/json");
        Res created = createOrder("u", "k-" + uniq(), items(p, 1), null);
        assertThat(created.header("Content-Type")).startsWith("application/json");
    }
}
