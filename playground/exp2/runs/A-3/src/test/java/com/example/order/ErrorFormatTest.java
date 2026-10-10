package com.example.order;

import com.example.order.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R11. 에러 포맷")
class ErrorFormatTest extends IntegrationTest {

    private static final Set<String> CODES = Set.of(
            "VALIDATION_ERROR", "PAYMENT_DECLINED", "PRODUCT_NOT_FOUND", "COUPON_NOT_FOUND", "ORDER_NOT_FOUND",
            "INSUFFICIENT_STOCK", "COUPON_NOT_APPLICABLE", "COUPON_EXHAUSTED", "DUPLICATE_COUPON_CODE",
            "INVALID_STATE", "IDEMPOTENCY_IN_PROGRESS", "IDEMPOTENCY_KEY_MISMATCH", "PAYMENT_GATEWAY_UNAVAILABLE");

    /** R11.1·R11.2: application/problem+json, type·title·status·detail·code */
    private static void assertProblemDetails(Resp r, int status, String code) {
        assertThat(r.status()).as(r.raw()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.json().path("type").isTextual()).isTrue();
        assertThat(r.json().path("title").isTextual()).isTrue();
        assertThat(r.json().path("status").asInt()).isEqualTo(status);
        assertThat(r.json().path("detail").isTextual()).isTrue();
        assertThat(r.json().path("code").asText()).isEqualTo(code);
        assertThat(CODES).contains(r.json().path("code").asText());
    }

    @Test
    @DisplayName("R11.1 요청 본문 JSON 파싱 실패는 400 Problem Details")
    void malformedJson() {
        assertProblemDetails(post("/api/products", "{\"name\": \"x\", "), 400, "VALIDATION_ERROR");
        assertProblemDetails(post("/api/coupons", "not json"), 400, "VALIDATION_ERROR");
        assertProblemDetails(post("/api/products", "{\"name\":\"x\",\"price\":\"abc\",\"stock\":1}"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 400 VALIDATION_ERROR (본문·헤더·쿼리)")
    void validation() {
        assertProblemDetails(post("/api/products", Map.of("name", "", "price", 1, "stock", 1)), 400, "VALIDATION_ERROR");
        assertProblemDetails(post("/api/orders", orderBody(null, List.of(item(1, 1))), Map.of("Idempotency-Key", "k")),
                400, "VALIDATION_ERROR");
        assertProblemDetails(get("/api/orders?size=0"), 400, "VALIDATION_ERROR");
        assertProblemDetails(get("/api/orders/not-a-number"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 402 PAYMENT_DECLINED")
    void declined() {
        long orderId = placeOrder(item(createProduct(1_000, 10), 1)).path("id").asLong();
        assertProblemDetails(pay(orderId, "tok_decline"), 402, "PAYMENT_DECLINED");
    }

    @Test
    @DisplayName("R11.3 404 PRODUCT_NOT_FOUND · COUPON_NOT_FOUND · ORDER_NOT_FOUND")
    void notFound() {
        assertProblemDetails(get("/api/products/987654321"), 404, "PRODUCT_NOT_FOUND");
        assertProblemDetails(get("/api/coupons/NOSUCHCODE"), 404, "COUPON_NOT_FOUND");
        assertProblemDetails(get("/api/orders/987654321"), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 409 INSUFFICIENT_STOCK · COUPON_NOT_APPLICABLE · COUPON_EXHAUSTED · DUPLICATE_COUPON_CODE · INVALID_STATE")
    void conflicts() {
        long productId = createProduct(1_000, 1);
        assertProblemDetails(createOrder(uniqueUser(), null, List.of(item(productId, 2))), 409, "INSUFFICIENT_STOCK");

        Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 100);
        body.put("minOrderAmount", 1_000_000);
        assertProblemDetails(createOrder(uniqueUser(), createCoupon(body), List.of(item(productId, 1))),
                409, "COUPON_NOT_APPLICABLE");

        Map<String, Object> single = couponBody(uniqueCouponCode(), "FIXED", 100);
        single.put("totalQuantity", 1);
        String code = createCoupon(single);
        long other = createProduct(1_000, 10);
        placeOrder(uniqueUser(), code, item(other, 1));
        assertProblemDetails(createOrder(uniqueUser(), code, List.of(item(other, 1))), 409, "COUPON_EXHAUSTED");

        assertProblemDetails(post("/api/coupons", couponBody(code, "FIXED", 1)), 409, "DUPLICATE_COUPON_CODE");

        long pending = placeOrder(item(other, 1)).path("id").asLong();
        assertProblemDetails(post("/api/orders/" + pending + "/ship", null), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R11.3 422 IDEMPOTENCY_KEY_MISMATCH")
    void mismatch() {
        long productId = createProduct(1_000, 10);
        String key = uniqueKey();
        createOrder(uniqueUser(), key, orderBody(null, List.of(item(productId, 1))));
        assertProblemDetails(createOrder(uniqueUser(), key, orderBody(null, List.of(item(productId, 1)))),
                422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R11.3 503 PAYMENT_GATEWAY_UNAVAILABLE")
    void gatewayUnavailable() {
        long orderId = placeOrder(item(createProduct(1_000, 10), 1)).path("id").asLong();
        assertProblemDetails(pay(orderId, "tok_500"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
    }

    @Test
    @DisplayName("R11.3 409 IDEMPOTENCY_IN_PROGRESS (처리 중인 키 재사용)")
    void inProgress() throws Exception {
        long orderId = placeOrder(item(createProduct(1_000, 10), 1)).path("id").asLong();
        String key = uniqueKey();
        List<Resp> responses = IdempotencyTest.runConcurrently(2, () -> pay(orderId, key, "tok_slow_ok"));
        Resp blocked = responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
        assertProblemDetails(blocked, 409, "IDEMPOTENCY_IN_PROGRESS");
    }
}
