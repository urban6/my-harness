package com.example.order.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Response;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * R11. 모든 오류 응답은 application/problem+json + {type,title,status,detail,code} 이고,
 * 표의 code 13개가 각각 해당 상태코드로 실제 발생한다. (형태 단언은 assertProblem 이 수행한다)
 */
class ErrorFormatTest extends IntegrationTestBase {

    // ------------------------------------------------------------------ R11.3 code 전수

    @Test
    @DisplayName("R11.3 400 VALIDATION_ERROR — 본문 검증")
    void code_validationErrorFromBody() {
        assertProblem(post("/api/products", map("name", "", "price", 1, "stock", 1)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 400 VALIDATION_ERROR — 헤더 검증")
    void code_validationErrorFromHeader() {
        long p = newProduct(1000, 5);

        assertProblem(post("/api/orders", map("items", items(p, 1)), "X-User-Id", uniqueUser()), 400,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 400 VALIDATION_ERROR — 쿼리 검증")
    void code_validationErrorFromQuery() {
        assertProblem(get("/api/orders?size=0"), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders?status=NOPE"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 400 VALIDATION_ERROR — 경로 변수 변환 실패")
    void code_validationErrorFromPath() {
        assertProblem(get("/api/products/abc"), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders/abc"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.1 요청 본문 JSON 파싱 실패(깨진 JSON)는 400 problem+json VALIDATION_ERROR")
    void jsonParseFailureIsProblemJson() {
        String[] bodies = {"{\"name\":", "not json at all", "{\"name\":\"x\",}", "[1,2,", "{'name':'x'}"};
        for (String body : bodies) {
            assertProblem(exchange(HttpMethod.POST, "/api/products", body), 400, "VALIDATION_ERROR");
        }
        assertProblem(exchange(HttpMethod.POST, "/api/coupons", "{\"code\":"), 400, "VALIDATION_ERROR");
        assertProblem(exchange(HttpMethod.POST, "/api/orders", "{\"items\":[",
                "X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey()), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.1 요청 본문이 없거나 타입이 맞지 않아도 400 problem+json")
    void missingOrWronglyTypedBodyIsProblemJson() {
        assertProblem(post("/api/products", null), 400, "VALIDATION_ERROR");
        assertProblem(exchange(HttpMethod.POST, "/api/products", "\"just a string\""), 400, "VALIDATION_ERROR");
        assertProblem(exchange(HttpMethod.POST, "/api/products", "{\"name\":\"x\",\"price\":\"abc\",\"stock\":1}"), 400,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 402 PAYMENT_DECLINED")
    void code_paymentDeclined() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();

        assertProblem(pay(orderId, uniqueKey(), "decline_card"), 402, "PAYMENT_DECLINED");
    }

    @Test
    @DisplayName("R11.3 404 PRODUCT_NOT_FOUND — 조회와 주문 항목 모두")
    void code_productNotFound() {
        assertProblem(get("/api/products/999999999"), 404, "PRODUCT_NOT_FOUND");
        assertProblem(placeOrder(uniqueUser(), uniqueKey(), null, items(999_999_999L, 1)), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 404 COUPON_NOT_FOUND — 조회와 주문 쿠폰 모두")
    void code_couponNotFound() {
        long p = newProduct(1000, 5);

        assertProblem(get("/api/coupons/NOSUCHCODE9"), 404, "COUPON_NOT_FOUND");
        assertProblem(placeOrder(uniqueUser(), "NOSUCHCODE9", p, 1), 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 404 ORDER_NOT_FOUND — 조회·결제·취소·배송·완료 모두")
    void code_orderNotFound() {
        long missing = 999_999_999L;

        assertProblem(get("/api/orders/" + missing), 404, "ORDER_NOT_FOUND");
        assertProblem(pay(missing), 404, "ORDER_NOT_FOUND");
        assertProblem(cancel(missing), 404, "ORDER_NOT_FOUND");
        assertProblem(ship(missing), 404, "ORDER_NOT_FOUND");
        assertProblem(deliver(missing), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 409 INSUFFICIENT_STOCK")
    void code_insufficientStock() {
        assertProblem(placeOrder(uniqueUser(), null, newProduct(1000, 1), 2), 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("R11.3 409 COUPON_NOT_APPLICABLE")
    void code_couponNotApplicable() {
        String code = couponWithMinimum(1_000_000);

        assertProblem(placeOrder(uniqueUser(), code, newProduct(1000, 5), 1), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R11.3 409 COUPON_EXHAUSTED")
    void code_couponExhausted() {
        String code = newCoupon("FIXED", 100, 1);
        long p = newProduct(1000, 5);
        newOrder(uniqueUser(), code, items(p, 1));

        assertProblem(placeOrder(uniqueUser(), code, p, 1), 409, "COUPON_EXHAUSTED");
    }

    @Test
    @DisplayName("R11.3 409 DUPLICATE_COUPON_CODE")
    void code_duplicateCouponCode() {
        String code = newCoupon("FIXED", 100, 1);

        assertProblem(post("/api/coupons", couponBody(code, "FIXED", 100)), 409, "DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("R11.3 409 INVALID_STATE")
    void code_invalidState() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();

        assertProblem(ship(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R11.3 409 IDEMPOTENCY_IN_PROGRESS — 같은 키의 결제가 PG 호출 중일 때")
    void code_idempotencyInProgress() throws Exception {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        String key = uniqueKey();
        PG.respondWith(r -> Response.json("{\"paymentId\":\"p\",\"status\":\"APPROVED\"}").delayed(1000));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<JsonNode>> first = pool.submit(() -> pay(orderId, key, "tok_ok"));
            awaitGatewayRequests(1);

            assertProblem(pay(orderId, key, "tok_ok"), 409, "IDEMPOTENCY_IN_PROGRESS");

            assertStatus(first.get(30, TimeUnit.SECONDS), 200);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("R11.3 422 IDEMPOTENCY_KEY_MISMATCH")
    void code_idempotencyKeyMismatch() {
        long p = newProduct(1000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        placeOrder(user, key, null, items(p, 1));

        assertProblem(placeOrder(user, key, null, items(p, 2)), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R11.3 503 PAYMENT_GATEWAY_UNAVAILABLE")
    void code_paymentGatewayUnavailable() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        PG.respondWith(r -> Response.status(500));

        assertProblem(pay(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
    }

    // ------------------------------------------------------------------ R11.1 / R11.2 형태 세부

    @Test
    @DisplayName("R11.2 type 은 URI 이고 status 필드는 HTTP 상태코드와 같은 정수, code 는 표의 값이다")
    void problemFieldsAreWellFormed() {
        List<ResponseEntity<JsonNode>> errors = new ArrayList<>();
        errors.add(get("/api/products/999999999"));
        errors.add(post("/api/products", map("name", "", "price", 1, "stock", 1)));
        errors.add(placeOrder(uniqueUser(), uniqueKey(), null, items(newProduct(1000, 1), 5)));
        errors.add(cancel(999_999_999L));

        for (ResponseEntity<JsonNode> res : errors) {
            JsonNode b = res.getBody();
            assertThat(URI.create(b.get("type").asText())).isNotNull();
            assertThat(b.get("status").asInt()).isEqualTo(res.getStatusCode().value());
            assertThat(b.get("code").asText()).matches("[A-Z_]+");
        }
    }

    @Test
    @DisplayName("R11.1 Accept: application/json 로 요청해도 오류는 406 이 아니라 application/problem+json 으로 돌아온다")
    void problemJsonEvenWhenClientAcceptsOnlyJson() {
        ResponseEntity<JsonNode> notFound = exchange(HttpMethod.GET, "/api/products/999999999", null, "Accept",
                "application/json");
        ResponseEntity<JsonNode> invalid = exchange(HttpMethod.POST, "/api/products",
                map("name", "", "price", 1, "stock", 1), "Accept", "application/json");

        assertProblem(notFound, 404, "PRODUCT_NOT_FOUND");
        assertProblem(invalid, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.1 오류 응답 본문에 스택 트레이스·예외 클래스명이 새지 않는다")
    void errorBodyDoesNotLeakInternals() {
        ResponseEntity<JsonNode> res = exchange(HttpMethod.POST, "/api/products", "{\"name\":");

        String text = res.getBody().toString();
        assertThat(text).doesNotContain("Exception").doesNotContain("at com.example").doesNotContain("trace");
    }

    private String couponWithMinimum(long minimum) {
        var body = couponBody(uniqueCode(), "FIXED", 100);
        body.put("minOrderAmount", minimum);
        return createCoupon(body).get("code").asText();
    }
}
