package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("R11 에러 포맷 (RFC 9457)")
class R11ErrorFormatTest extends AbstractIntegrationTest {

    /** R11.1/R11.2/R11.3: problem+json, 필수 필드, status 일치, code 일치. */
    private void assertProblem(ApiResponse r, int status, String code) {
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.contentType()).isNotNull().startsWith("application/problem+json");
        assertThat(r.body()).isNotNull();
        assertThat(r.body().hasNonNull("type")).as("type").isTrue();
        assertThat(r.body().hasNonNull("title")).as("title").isTrue();
        assertThat(r.body().hasNonNull("detail")).as("detail").isTrue();
        assertThat(r.body().get("status").asInt()).as("status field").isEqualTo(status);
        assertThat(r.body().get("code").asText()).as("code").isEqualTo(code);
        assertThat(r.body().get("type").asText()).isNotBlank();
        assertThat(r.body().get("title").asText()).isNotBlank();
    }

    // ------------------------------------------------------------- R11.3 code table (one trigger per code)

    @Test
    @DisplayName("R11.3 400 VALIDATION_ERROR")
    void code_validationError() {
        assertProblem(createProduct("", 0, -1), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 402 PAYMENT_DECLINED")
    void code_paymentDeclined() {
        ApiResponse order = newOrder(uniqueUser(), null, newProduct(1_000, 5), 1);
        stubPgPayment("DECLINED", "pay-no");

        assertProblem(pay(order.id(), uniqueKey(), "tok"), 402, "PAYMENT_DECLINED");
    }

    @Test
    @DisplayName("R11.3 404 PRODUCT_NOT_FOUND (상품 조회 / 주문 생성)")
    void code_productNotFound() {
        assertProblem(getProduct(Long.MAX_VALUE - 1), 404, "PRODUCT_NOT_FOUND");
        assertProblem(createOrder(uniqueUser(), uniqueKey(), null, Long.MAX_VALUE - 1, 1), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 404 COUPON_NOT_FOUND (쿠폰 조회 / 주문 생성)")
    void code_couponNotFound() {
        long p = newProduct(1_000, 5);
        assertProblem(getCoupon("NOPE" + System.nanoTime()), 404, "COUPON_NOT_FOUND");
        assertProblem(createOrder(uniqueUser(), uniqueKey(), "NOPE" + System.nanoTime(), p, 1), 404,
                "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 404 ORDER_NOT_FOUND (조회/결제/취소/배송)")
    void code_orderNotFound() {
        long id = Long.MAX_VALUE - 1;
        assertProblem(getOrder(id), 404, "ORDER_NOT_FOUND");
        assertProblem(pay(id, uniqueKey(), "tok"), 404, "ORDER_NOT_FOUND");
        assertProblem(cancel(id), 404, "ORDER_NOT_FOUND");
        assertProblem(ship(id), 404, "ORDER_NOT_FOUND");
        assertProblem(deliver(id), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 409 INSUFFICIENT_STOCK")
    void code_insufficientStock() {
        assertProblem(createOrder(uniqueUser(), uniqueKey(), null, newProduct(1_000, 1), 2), 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("R11.3 409 COUPON_NOT_APPLICABLE")
    void code_couponNotApplicable() {
        String coupon = newCoupon("FIXED", 100, 1_000_000L, null, 5);

        assertProblem(createOrder(uniqueUser(), uniqueKey(), coupon, newProduct(1_000, 5), 1), 409,
                "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R11.3 409 COUPON_EXHAUSTED")
    void code_couponExhausted() {
        String coupon = newCoupon("FIXED", 100, null, null, 1);
        long p = newProduct(1_000, 5);
        newOrder(uniqueUser(), coupon, p, 1);

        assertProblem(createOrder(uniqueUser(), uniqueKey(), coupon, p, 1), 409, "COUPON_EXHAUSTED");
    }

    @Test
    @DisplayName("R11.3 409 DUPLICATE_COUPON_CODE")
    void code_duplicateCouponCode() {
        String coupon = newCoupon("FIXED", 100, null, null, 1);

        assertProblem(createCoupon(coupon, "FIXED", 100, 1), 409, "DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("R11.3 409 INVALID_STATE")
    void code_invalidState() {
        ApiResponse order = newOrder(uniqueUser(), null, newProduct(1_000, 5), 1);

        assertProblem(ship(order.id()), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R11.3 409 IDEMPOTENCY_IN_PROGRESS")
    void code_idempotencyInProgress() {
        ApiResponse order = newOrder(uniqueUser(), null, newProduct(1_000, 5), 1);
        stubPgPaymentDelayed("APPROVED", "pay-slow", 1_500);
        String key = uniqueKey();
        CompletableFuture<ApiResponse> first = CompletableFuture.supplyAsync(() -> pay(order.id(), key, "tok"));
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .until(() -> pgPaymentRequestCount() >= 1);

        ApiResponse second = pay(order.id(), key, "tok");

        assertProblem(second, 409, "IDEMPOTENCY_IN_PROGRESS");
        assertThat(first.join().status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R11.3 422 IDEMPOTENCY_KEY_MISMATCH")
    void code_idempotencyKeyMismatch() {
        long p = newProduct(1_000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        createOrder(user, key, null, p, 1);

        assertProblem(createOrder(user, key, null, p, 2), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R11.3 503 PAYMENT_GATEWAY_UNAVAILABLE (결제 / 환불)")
    void code_paymentGatewayUnavailable() {
        long p = newProduct(1_000, 5);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPaymentStatus(500);
        assertProblem(pay(order.id(), uniqueKey(), "tok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        WIREMOCK.resetAll();
        ApiResponse paid = newPaidOrder(uniqueUser(), null, p, 1);
        stubPgRefundStatus(500);
        assertProblem(cancel(paid.id()), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
    }

    // ------------------------------------------------------------- R11.1 JSON parse failure / runtime items

    @Test
    @DisplayName("R11.1 깨진 JSON 본문 -> 400 problem+json (상품·쿠폰·주문·결제 모두)")
    void malformedJson_400_everywhere() {
        long p = newProduct(1_000, 5);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);

        assertProblem(post("/api/products", "{not json", null), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/coupons", "{\"code\":", null), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders", "[[[", headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey())),
                400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/" + order.id() + "/pay", "{\"cardToken\":",
                headers("Idempotency-Key", uniqueKey())), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.1 본문이 비어 있는 POST -> 400")
    void emptyBody_400() {
        ApiResponse r = send("POST", "/api/products", "", null);

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "C1/C2 런타임: 문자열 숫자 {0} -> 400")
    @ValueSource(strings = {
            "{\"name\":\"x\",\"price\":\"100\",\"stock\":1}",
            "{\"name\":\"x\",\"price\":100,\"stock\":\"1\"}"})
    void stringNumbers_400(String body) {
        assertProblem(post("/api/products", body, null), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C1 런타임: 소수 / 지수 표기 / long 범위 초과 숫자 -> 400")
    void nonIntegerOrOverflowNumbers_400() {
        assertProblem(post("/api/products", "{\"name\":\"x\",\"price\":1.5,\"stock\":1}", null), 400,
                "VALIDATION_ERROR");
        assertProblem(post("/api/products", "{\"name\":\"x\",\"price\":99999999999999999999,\"stock\":1}", null),
                400, "VALIDATION_ERROR");
        assertProblem(post("/api/products", "{\"name\":\"x\",\"price\":100,\"stock\":3000000000}", null), 400,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C1 런타임: name 이 문자열이 아닌 값(숫자/객체) -> 400")
    void nonStringName_400() {
        assertProblem(post("/api/products", "{\"name\":{\"a\":1},\"price\":100,\"stock\":1}", null), 400,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C2 런타임: 오프셋 없는 시각 -> 400")
    void timestampWithoutOffset_400() {
        String body = "{\"code\":\"" + uniqueCode() + "\",\"type\":\"FIXED\",\"value\":10,\"totalQuantity\":1,"
                + "\"validFrom\":\"2030-01-01T00:00:00\",\"validUntil\":\"2031-01-01T00:00:00Z\"}";

        assertProblem(post("/api/coupons", body, null), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C2 런타임: ISO-8601 이 아닌 시각 문자열 -> 400")
    void invalidTimestampString_400() {
        String body = "{\"code\":\"" + uniqueCode() + "\",\"type\":\"FIXED\",\"value\":10,\"totalQuantity\":1,"
                + "\"validFrom\":\"yesterday\",\"validUntil\":\"2031-01-01T00:00:00Z\"}";

        assertProblem(post("/api/coupons", body, null), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C2 런타임: 시각을 숫자(epoch)로 보내면 -> 400 (시각은 ISO-8601 문자열이어야 한다)")
    void numericTimestamp_400() {
        String body = "{\"code\":\"" + uniqueCode() + "\",\"type\":\"FIXED\",\"value\":10,\"totalQuantity\":1,"
                + "\"validFrom\":1700000000,\"validUntil\":\"2031-01-01T00:00:00Z\"}";

        assertProblem(post("/api/coupons", body, null), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C2 오프셋이 있는 다양한 ISO-8601 표기는 허용 (+09:00, Z, 소수초)")
    void timestampWithOffsets_accepted() {
        String body = "{\"code\":\"" + uniqueCode() + "\",\"type\":\"FIXED\",\"value\":10,\"totalQuantity\":1,"
                + "\"validFrom\":\"2030-01-01T09:00:00.123456+09:00\",\"validUntil\":\"2031-01-01T00:00:00Z\"}";

        ApiResponse r = post("/api/coupons", body, null);

        assertThat(r.status()).isEqualTo(201);
        assertThat(instant(r.json("validFrom"))).isEqualTo(java.time.Instant.parse("2030-01-01T00:00:00.123456Z"));
    }

    @Test
    @DisplayName("R11 런타임: 존재하지 않는 enum type 값 -> 400")
    void unknownEnum_400() {
        String body = "{\"code\":\"" + uniqueCode() + "\",\"type\":\"PERCENT\",\"value\":10,\"totalQuantity\":1,"
                + "\"validFrom\":\"2030-01-01T00:00:00Z\",\"validUntil\":\"2031-01-01T00:00:00Z\"}";

        assertProblem(post("/api/coupons", body, null), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11 런타임: 공백 헤더 / 헤더 누락 -> 400 problem+json")
    void blankOrMissingHeaders_400() {
        long p = newProduct(1_000, 5);
        var body = orderBody(null, p, 1);

        assertProblem(post("/api/orders", body, headers("X-User-Id", " ", "Idempotency-Key", uniqueKey())), 400,
                "VALIDATION_ERROR");
        assertProblem(post("/api/orders", body, headers("X-User-Id", uniqueUser(), "Idempotency-Key", " ")), 400,
                "VALIDATION_ERROR");
        assertProblem(post("/api/orders", body, headers("Idempotency-Key", uniqueKey())), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders", body, headers("X-User-Id", uniqueUser())), 400, "VALIDATION_ERROR");
        assertThat(reservedOf(p)).isZero();
    }

    @Test
    @DisplayName("R11 쿼리/경로 변환 실패(status, size, id) -> 400 problem+json")
    void queryAndPathConversion_400() {
        assertProblem(listOrders("status=NOPE"), 400, "VALIDATION_ERROR");
        assertProblem(listOrders("size=abc"), 400, "VALIDATION_ERROR");
        assertProblem(listOrders("cursor=@@@"), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/products/abc"), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders/99999999999999999999"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.2 type 은 URI 형태 문자열, instance/errors 등 확장 필드는 있어도 무방")
    void typeIsUriLike() {
        ApiResponse r = getProduct(Long.MAX_VALUE - 1);

        assertProblem(r, 404, "PRODUCT_NOT_FOUND");
        assertThat(java.net.URI.create(r.body().get("type").asText())).isNotNull();
    }

    @Test
    @DisplayName("R11.1 Accept: application/json 이어도 오류 Content-Type 은 problem+json")
    void acceptJson_stillProblemJson() {
        ApiResponse r = send("GET", "/api/products/" + (Long.MAX_VALUE - 1), null,
                headers("Accept", "application/json"));

        assertProblem(r, 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R11 오류가 5xx(code 없는 500) 로 새지 않는다: 대표 잘못된 입력들이 모두 4xx")
    void badInputs_neverProduce500() {
        long p = newProduct(1_000, 5);
        String h = uniqueUser();
        var hs = headers("X-User-Id", h, "Idempotency-Key", uniqueKey());
        assertThat(post("/api/orders", "{\"items\":[{\"productId\":\"abc\",\"quantity\":1}]}", hs).status())
                .isEqualTo(400);
        assertThat(post("/api/orders", "{\"items\":[{\"productId\":" + p + ",\"quantity\":99999999999}]}",
                headers("X-User-Id", h, "Idempotency-Key", uniqueKey())).status()).isEqualTo(400);
        assertThat(post("/api/orders", "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}],\"couponCode\":[1]}",
                headers("X-User-Id", h, "Idempotency-Key", uniqueKey())).status()).isEqualTo(400);
        assertThat(post("/api/orders", "{\"items\":[{\"productId\":-5,\"quantity\":1}]}",
                headers("X-User-Id", h, "Idempotency-Key", uniqueKey())).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("R11 회귀(F1): 문자열 필드 couponCode 에 숫자 123 -> 400 VALIDATION_ERROR problem+json, 예약 없음")
    void numericCouponCode_400() {
        long p = newProduct(1_000, 5);
        String body = "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}],\"couponCode\":123}";

        ApiResponse r = post("/api/orders", body, headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey()));

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(reservedOf(p)).isZero();
    }

    @Test
    @DisplayName("R11 회귀(F1): 문자열 필드 cardToken 에 숫자 123 -> 400 VALIDATION_ERROR problem+json, PG 미호출")
    void numericCardToken_400() {
        long p = newProduct(1_000, 5);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("APPROVED", "pay-x");

        ApiResponse r = post("/api/orders/" + order.id() + "/pay", "{\"cardToken\":123}",
                headers("Idempotency-Key", uniqueKey()));

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(pgPaymentRequestCount()).isZero();
        assertThat(statusOf(order.id())).isEqualTo("PENDING_PAYMENT");
    }
}
