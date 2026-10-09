package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@DisplayName("R11. 에러 포맷 (RFC 9457 Problem Details)")
class ErrorFormatTest extends IntegrationTestSupport {

    @Test
    @DisplayName("R11.1~R11.3 정의된 모든 오류 code가 application/problem+json과 type·title·status·detail·code로 내려온다")
    void allErrorCodes() throws Exception {
        Map<String, ResponseEntity<JsonNode>> byCode = new LinkedHashMap<>();
        long p = createProduct(1_000, 1);
        long big = createProduct(1_000, 100);

        byCode.put("VALIDATION_ERROR", post("/api/products", map("name", "", "price", 0, "stock", -1)));
        byCode.put("PRODUCT_NOT_FOUND", get("/api/products/999999999"));
        byCode.put("COUPON_NOT_FOUND", get("/api/coupons/NOPE1234"));
        byCode.put("ORDER_NOT_FOUND", get("/api/orders/999999999"));
        byCode.put("INSUFFICIENT_STOCK", createOrder(newUser(), newKey(), orderBody(null, item(p, 2))));

        String minCoupon = createCoupon("minOrderAmount", 1_000_000);
        byCode.put("COUPON_NOT_APPLICABLE", createOrder(newUser(), newKey(), orderBody(minCoupon, item(big, 1))));
        String oneCoupon = createCoupon("totalQuantity", 1);
        placeOrder(newUser(), oneCoupon, item(big, 1));
        byCode.put("COUPON_EXHAUSTED", createOrder(newUser(), newKey(), orderBody(oneCoupon, item(big, 1))));
        byCode.put("DUPLICATE_COUPON_CODE", post("/api/coupons", couponRequest(oneCoupon)));

        long paid = paidOrder(newUser(), null, item(big, 1)).get("id").asLong();
        byCode.put("INVALID_STATE", post("/api/orders/" + paid + "/deliver", null));

        long declined = placeOrder(newUser(), null, item(big, 1)).get("id").asLong();
        byCode.put("PAYMENT_DECLINED", pay(declined, newKey(), "decline-card"));

        String key = newKey();
        String user = newUser();
        createOrder(user, key, orderBody(null, item(big, 1)));
        byCode.put("IDEMPOTENCY_KEY_MISMATCH", createOrder(user, key, orderBody(null, item(big, 2))));

        // 같은 키의 결제가 처리 중(PG 응답 대기)일 때 들어온 같은 요청
        long slow = placeOrder(newUser(), null, item(big, 1)).get("id").asLong();
        String payKey = newKey();
        CompletableFuture<ResponseEntity<JsonNode>> first = CompletableFuture.supplyAsync(() -> pay(slow, payKey, "timeout-card"));
        Thread.sleep(500);
        byCode.put("IDEMPOTENCY_IN_PROGRESS", pay(slow, payKey, "timeout-card"));
        byCode.put("PAYMENT_GATEWAY_UNAVAILABLE", first.get());

        Map<String, Integer> expectedStatus = Map.ofEntries(
                Map.entry("VALIDATION_ERROR", 400),
                Map.entry("PAYMENT_DECLINED", 402),
                Map.entry("PRODUCT_NOT_FOUND", 404),
                Map.entry("COUPON_NOT_FOUND", 404),
                Map.entry("ORDER_NOT_FOUND", 404),
                Map.entry("INSUFFICIENT_STOCK", 409),
                Map.entry("COUPON_NOT_APPLICABLE", 409),
                Map.entry("COUPON_EXHAUSTED", 409),
                Map.entry("DUPLICATE_COUPON_CODE", 409),
                Map.entry("INVALID_STATE", 409),
                Map.entry("IDEMPOTENCY_IN_PROGRESS", 409),
                Map.entry("IDEMPOTENCY_KEY_MISMATCH", 422),
                Map.entry("PAYMENT_GATEWAY_UNAVAILABLE", 503));
        assertThat(byCode.keySet()).containsExactlyInAnyOrderElementsOf(expectedStatus.keySet());

        byCode.forEach((code, response) -> {
            assertProblem(response, expectedStatus.get(code), code);
            JsonNode body = response.getBody();
            assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
            assertThat(URI.create(body.get("type").asText())).isNotNull();
            assertThat(body.get("title").asText()).isNotBlank();
            assertThat(body.get("detail").asText()).isNotBlank();
        });
    }

    @Test
    @DisplayName("R11.1/R11.3 JSON 파싱 실패, 헤더 위반, 쿼리 위반도 400 VALIDATION_ERROR problem+json")
    void validationSources() {
        assertProblem(post("/api/products", "{not json"), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/coupons", "[1,2]"), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders", "{\"items\":[{\"productId\":1,\"quantity\":1}]}", "Idempotency-Key", "k"),
                400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders?size=1000"), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders/not-a-number"), 400, "VALIDATION_ERROR");
    }
}
