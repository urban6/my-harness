package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api.Response;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R11 에러 포맷 · C3 오류 우선순위")
class ErrorFormatApiTest extends IntegrationTest {

    private static void assertProblemDetails(Response r, int status, String code) {
        assertProblem(r, status, code);
        JsonNode body = r.body();
        assertThat(body.get("type").asText()).isNotBlank();
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("detail").asText()).isNotBlank();
        assertThat(body.get("code").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("R11.1~R11.3 모든 오류는 application/problem+json, type·title·status·detail·code 포함")
    void problemDetails() {
        long p = createProduct(1000, 1);
        String exhausted = createCoupon("FIXED", 100, 1);
        createOrder(uniqueUser(), exhausted, createProduct(1000, 10), 1);
        String user = uniqueUser();
        String key = uniqueKey();
        postOrder(user, key, orderBody(null, p, 1));
        long paid = paidOrder(uniqueUser(), null, "tok", createProduct(1000, 10), 1).get("id").asLong();
        long pending = createOrder(uniqueUser(), null, createProduct(1000, 10), 1).get("id").asLong();
        long pending2 = createOrder(uniqueUser(), null, createProduct(1000, 10), 1).get("id").asLong();
        String duplicate = createCoupon("FIXED", 100, 1);

        assertProblemDetails(api.post("/api/products", "{\"name\":"), 400, "VALIDATION_ERROR");
        assertProblemDetails(api.post("/api/products", "not json"), 400, "VALIDATION_ERROR");
        assertProblemDetails(api.post("/api/products", "{\"name\":\"x\",\"price\":0,\"stock\":1}"), 400,
                "VALIDATION_ERROR");
        assertProblemDetails(api.get("/api/orders?size=0"), 400, "VALIDATION_ERROR");
        assertProblemDetails(api.get("/api/orders/abc"), 400, "VALIDATION_ERROR");
        assertProblemDetails(pay(pending, uniqueKey(), "decline"), 402, "PAYMENT_DECLINED");
        assertProblemDetails(api.get("/api/products/987654321"), 404, "PRODUCT_NOT_FOUND");
        assertProblemDetails(api.get("/api/coupons/NOPE9999"), 404, "COUPON_NOT_FOUND");
        assertProblemDetails(api.get("/api/orders/987654321"), 404, "ORDER_NOT_FOUND");
        assertProblemDetails(postOrder(uniqueUser(), uniqueKey(), orderBody(null, p, 5)), 409,
                "INSUFFICIENT_STOCK");
        assertProblemDetails(postOrder(uniqueUser(), uniqueKey(),
                orderBody(exhausted, createProduct(1000, 10), 1)), 409, "COUPON_EXHAUSTED");
        assertProblemDetails(api.post("/api/coupons", """
                {"code":"%s","type":"FIXED","value":1,"totalQuantity":1,
                 "validFrom":"2020-01-01T00:00:00Z","validUntil":"2099-01-01T00:00:00Z"}""".formatted(duplicate)),
                409, "DUPLICATE_COUPON_CODE");
        assertProblemDetails(api.post("/api/orders/" + paid + "/deliver", null), 409, "INVALID_STATE");
        assertProblemDetails(postOrder(user, key, orderBody(null, p, 2)), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertProblemDetails(pay(pending2, uniqueKey(), "error"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
    }

    @Test
    @DisplayName("C3 400이 멱등 키 불일치(422)·404보다 먼저")
    void validationFirst() {
        long p = createProduct(1000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        postOrder(user, key, orderBody(null, p, 1));

        assertProblem(postOrder(user, key, orderBody(null, 987654321L, 0)), 400, "VALIDATION_ERROR");
        assertProblem(postOrder(user, key, "{\"items\":"), 400, "VALIDATION_ERROR");
        assertProblem(api.post("/api/orders/987654321/pay", "{\"cardToken\":\"\"}", "Idempotency-Key", key),
                400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 멱등 키(422)가 404보다 먼저")
    void idempotencyBeforeNotFound() {
        long p = createProduct(1000, 10);
        String user = uniqueUser();
        String key = uniqueKey();
        postOrder(user, key, orderBody(null, p, 1));
        assertProblem(postOrder(user, key, orderBody("NOSUCHCOUPON", 987654321L, 1)), 422,
                "IDEMPOTENCY_KEY_MISMATCH");

        long orderId = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        String payKey = uniqueKey();
        pay(orderId, payKey, "tok");
        assertProblem(pay(987654321L, payKey, "tok"), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 404가 409보다 먼저, 409는 재고가 쿠폰보다 먼저")
    void notFoundBeforeConflict() {
        long scarce = createProduct(1000, 1);
        String exhausted = createCoupon("FIXED", 100, 1);
        createOrder(uniqueUser(), exhausted, createProduct(1000, 10), 1);

        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody(null, scarce, 5, 987654321L, 1)), 404,
                "PRODUCT_NOT_FOUND");
        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody("NOSUCHCOUPON", scarce, 5)), 404,
                "COUPON_NOT_FOUND");
        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody(exhausted, scarce, 5)), 409,
                "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 409가 PG 결과(402·503)보다 먼저")
    void conflictBeforeGateway() {
        long p = createProduct(1000, 10);
        long paid = paidOrder(uniqueUser(), null, "tok", p, 1).get("id").asLong();
        for (String token : List.of("error", "decline")) {
            assertProblem(pay(paid, uniqueKey(), token), 409, "INVALID_STATE");
        }
    }
}
