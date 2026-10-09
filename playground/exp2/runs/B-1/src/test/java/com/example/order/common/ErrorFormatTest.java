package com.example.order.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.FakePaymentGateway.Mode;
import com.example.order.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R11: 정의된 모든 오류가 RFC 9457 Problem Details(type·title·status·detail·code)로 나간다. */
@DisplayName("R11 에러 포맷")
class ErrorFormatTest extends IntegrationTest {

    @Test
    @DisplayName("R11.1 JSON 파싱 실패는 400 VALIDATION_ERROR problem+json")
    void malformedJson() {
        assertProblem(api.postRaw("/api/products", "{\"name\":"), 400, "VALIDATION_ERROR");
        assertProblem(api.postRaw("/api/coupons", "not json"), 400, "VALIDATION_ERROR");
        long orderId = createOrder(uniqueUser(), null, List.of(item(createProduct(1_000, 1), 1)));
        assertProblem(api.postRaw("/api/orders/" + orderId + "/pay", "{", "Idempotency-Key", uniqueKey()),
                400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 400 은 본문·헤더·쿼리·경로 검증 모두 VALIDATION_ERROR")
    void validationErrors() {
        assertProblem(api.post("/api/products", Map.of("name", "", "price", 0, "stock", -1)), 400, "VALIDATION_ERROR");
        assertProblem(api.post("/api/orders", Map.of("items", List.of())), 400, "VALIDATION_ERROR");
        assertProblem(api.get("/api/orders?size=0"), 400, "VALIDATION_ERROR");
        assertProblem(api.get("/api/orders/abc"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 404 코드: PRODUCT_NOT_FOUND · COUPON_NOT_FOUND · ORDER_NOT_FOUND")
    void notFoundCodes() {
        assertProblem(api.get("/api/products/987654321"), 404, "PRODUCT_NOT_FOUND");
        assertProblem(api.get("/api/coupons/MISSING1"), 404, "COUPON_NOT_FOUND");
        assertProblem(api.get("/api/orders/987654321"), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 409 코드: INSUFFICIENT_STOCK · COUPON_NOT_APPLICABLE · COUPON_EXHAUSTED · DUPLICATE_COUPON_CODE · INVALID_STATE")
    void conflictCodes() {
        long productId = createProduct(1_000, 1);
        assertProblem(placeOrder(uniqueUser(), null, List.of(item(productId, 2))), 409, "INSUFFICIENT_STOCK");

        String minOrder = createCoupon(Map.of("minOrderAmount", 1_000_000));
        assertProblem(placeOrder(uniqueUser(), minOrder, List.of(item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");

        String single = createCoupon(Map.of("totalQuantity", 1));
        long orderId = createOrder(uniqueUser(), single, List.of(item(productId, 1)));
        assertProblem(placeOrder(uniqueUser(), single, List.of(item(createProduct(1_000, 1), 1))),
                409, "COUPON_EXHAUSTED");

        assertProblem(api.post("/api/coupons", couponBody(single, Map.of())), 409, "DUPLICATE_COUPON_CODE");

        assertProblem(action(orderId, "deliver"), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R11.3 409 IDEMPOTENCY_IN_PROGRESS · 422 IDEMPOTENCY_KEY_MISMATCH")
    void idempotencyCodes() throws Exception {
        long orderId = createOrder(uniqueUser(), null, List.of(item(createProduct(1_000, 1), 1)));
        String key = uniqueKey();
        PG.latency(Duration.ofMillis(1_500));

        CompletableFuture<ApiResponse> first = CompletableFuture.supplyAsync(() -> pay(orderId, key, "tok"));
        Thread.sleep(500);
        assertProblem(pay(orderId, key, "tok"), 409, "IDEMPOTENCY_IN_PROGRESS");
        assertProblem(pay(orderId, key, "tok_other"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(first.get().status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R11.3 402 PAYMENT_DECLINED · 503 PAYMENT_GATEWAY_UNAVAILABLE")
    void paymentCodes() {
        long productId = createProduct(1_000, 10);
        long unavailable = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        PG.paymentMode(Mode.SERVER_ERROR);
        assertProblem(pay(unavailable), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        long declined = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        PG.paymentMode(Mode.DECLINE);
        assertProblem(pay(declined), 402, "PAYMENT_DECLINED");
    }

    @Test
    @DisplayName("R11.2 problem 본문 필드 값이 일관된다(status 는 HTTP 상태와 같고 code 는 확장 필드)")
    void problemFields() {
        ApiResponse res = api.get("/api/orders/987654321");
        assertThat(res.body().get("status").asInt()).isEqualTo(404);
        assertThat(res.body().get("type").asText()).isNotBlank();
        assertThat(res.body().get("title").asText()).isNotBlank();
        assertThat(res.body().get("detail").asText()).contains("987654321");
    }
}
