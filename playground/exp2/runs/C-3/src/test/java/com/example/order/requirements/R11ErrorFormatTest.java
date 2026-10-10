package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@DisplayName("R11. 에러 포맷 (RFC 9457 Problem Details)")
class R11ErrorFormatTest extends IntegrationTestBase {

    private static final Set<String> SPEC_CODES = Set.of("VALIDATION_ERROR", "PAYMENT_DECLINED", "PRODUCT_NOT_FOUND",
            "COUPON_NOT_FOUND", "ORDER_NOT_FOUND", "INSUFFICIENT_STOCK", "COUPON_NOT_APPLICABLE", "COUPON_EXHAUSTED",
            "DUPLICATE_COUPON_CODE", "INVALID_STATE", "IDEMPOTENCY_IN_PROGRESS", "IDEMPOTENCY_KEY_MISMATCH",
            "PAYMENT_GATEWAY_UNAVAILABLE");

    /** 시나리오 이름 -> 해당 오류를 일으키는 실제 요청. 매 호출마다 새 픽스처를 만든다. */
    private ResponseEntity<String> trigger(String scenario) {
        switch (scenario) {
            // ---- 400
            case "VALIDATION_BODY":
                return post("/api/products", Map.of("name", " ", "price", 1000, "stock", 1));
            case "VALIDATION_HEADER_MISSING":
                return post("/api/orders", orderBody(null, item(product(1_000, 5), 1)), "Idempotency-Key", uid("k"));
            case "VALIDATION_HEADER_TOO_LONG":
                return createOrder(uid("u"), "k".repeat(65), orderBody(null, item(product(1_000, 5), 1)));
            case "VALIDATION_QUERY_SIZE":
                return get("/api/orders?size=0");
            case "VALIDATION_QUERY_STATUS":
                return get("/api/orders?status=NOPE");
            case "VALIDATION_QUERY_CURSOR":
                return get("/api/orders?cursor=%21%21%21");
            case "VALIDATION_PATH":
                return get("/api/products/abc");
            case "JSON_MALFORMED_PRODUCT":
                return post("/api/products", "{\"name\":\"x\",");
            case "JSON_MALFORMED_COUPON":
                return post("/api/coupons", "not json at all");
            case "JSON_MALFORMED_ORDER":
                return createOrder(uid("u"), uid("k"), "{\"items\":[");
            case "JSON_MALFORMED_PAY":
                return postWithKey("/api/orders/1/pay", "{\"cardToken\":", uid("pk"));
            case "JSON_WRONG_TYPE":
                return post("/api/products", "{\"name\":\"x\",\"price\":\"cheap\",\"stock\":1}");
            case "JSON_EMPTY_BODY":
                return post("/api/products", null);
            case "JSON_WRONG_ROOT_TYPE":
                return post("/api/products", "[1,2,3]");
            // ---- 402
            case "PAYMENT_DECLINED": {
                long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
                PG.declinePayments();
                return pay(orderId, uid("pk"), "tok");
            }
            // ---- 404
            case "PRODUCT_NOT_FOUND_GET":
                return getProduct(987_654_321L);
            case "PRODUCT_NOT_FOUND_ORDER":
                return createOrder(uid("u"), uid("k"), orderBody(null, item(987_654_321L, 1)));
            case "COUPON_NOT_FOUND_GET":
                return getCoupon("NOSUCHCOUPON9");
            case "COUPON_NOT_FOUND_ORDER":
                return createOrder(uid("u"), uid("k"), orderBody("NOSUCHCOUPON9", item(product(1_000, 5), 1)));
            case "ORDER_NOT_FOUND_GET":
                return getOrder(987_654_321L);
            case "ORDER_NOT_FOUND_PAY":
                return pay(987_654_321L, uid("pk"), "tok");
            case "ORDER_NOT_FOUND_CANCEL":
                return cancel(987_654_321L);
            case "ORDER_NOT_FOUND_SHIP":
                return ship(987_654_321L);
            case "ORDER_NOT_FOUND_DELIVER":
                return deliver(987_654_321L);
            // ---- 409
            case "INSUFFICIENT_STOCK":
                return createOrder(uid("u"), uid("k"), orderBody(null, item(product(1_000, 1), 2)));
            case "COUPON_NOT_APPLICABLE": {
                String code = uniqueCode("R11A");
                createCoupon(code, "FIXED", 100, 10_000_000, null, 3);
                return createOrder(uid("u"), uid("k"), orderBody(code, item(product(1_000, 5), 1)));
            }
            case "COUPON_EXHAUSTED": {
                String code = uniqueCode("R11E");
                createCoupon(code, "FIXED", 100, 0, null, 1);
                long productId = product(1_000, 5);
                orderOk(uid("u"), orderBody(code, item(productId, 1)));
                return createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1)));
            }
            case "DUPLICATE_COUPON_CODE": {
                String code = uniqueCode("R11D");
                createCoupon(code, "FIXED", 100, 0, null, 1);
                return createCouponResponse(code, "FIXED", 100, 0, null, 1);
            }
            case "INVALID_STATE_SHIP":
                return ship(orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1))));
            case "INVALID_STATE_DELIVER":
                return deliver(orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1))));
            case "INVALID_STATE_CANCEL": {
                long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
                cancel(orderId);
                return cancel(orderId);
            }
            case "INVALID_STATE_PAY": {
                long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
                payOk(orderId);
                return pay(orderId, uid("pk"), "tok");
            }
            case "IDEMPOTENCY_IN_PROGRESS": {
                long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
                String key = uid("pk");
                PG.delayPayments(1_500);
                CompletableFuture<ResponseEntity<String>> first = CompletableFuture.supplyAsync(() -> pay(orderId, key, "tok"));
                assertThat(awaitCondition(java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(20),
                        () -> PG.paymentCalls() >= 1)).isTrue();
                ResponseEntity<String> concurrent = pay(orderId, key, "tok");
                first.join();
                return concurrent;
            }
            // ---- 422
            case "IDEMPOTENCY_KEY_MISMATCH": {
                long productId = product(1_000, 5);
                String user = uid("u");
                String key = uid("k");
                createOrder(user, key, orderBody(null, item(productId, 1)));
                return createOrder(user, key, orderBody(null, item(productId, 2)));
            }
            // ---- 503
            case "GATEWAY_5XX": {
                long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
                PG.failPaymentsWith5xx();
                return pay(orderId, uid("pk"), "tok");
            }
            case "GATEWAY_DOWN": {
                long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
                PG.stop();
                return pay(orderId, uid("pk"), "tok");
            }
            case "GATEWAY_REFUND_5XX": {
                long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
                payOk(orderId);
                PG.failRefundsWith5xx();
                return cancel(orderId);
            }
            default:
                throw new IllegalArgumentException(scenario);
        }
    }

    private static final String SCENARIOS = """
            VALIDATION_BODY,400,VALIDATION_ERROR
            VALIDATION_HEADER_MISSING,400,VALIDATION_ERROR
            VALIDATION_HEADER_TOO_LONG,400,VALIDATION_ERROR
            VALIDATION_QUERY_SIZE,400,VALIDATION_ERROR
            VALIDATION_QUERY_STATUS,400,VALIDATION_ERROR
            VALIDATION_QUERY_CURSOR,400,VALIDATION_ERROR
            VALIDATION_PATH,400,VALIDATION_ERROR
            JSON_MALFORMED_PRODUCT,400,VALIDATION_ERROR
            JSON_MALFORMED_COUPON,400,VALIDATION_ERROR
            JSON_MALFORMED_ORDER,400,VALIDATION_ERROR
            JSON_MALFORMED_PAY,400,VALIDATION_ERROR
            JSON_WRONG_TYPE,400,VALIDATION_ERROR
            JSON_EMPTY_BODY,400,VALIDATION_ERROR
            JSON_WRONG_ROOT_TYPE,400,VALIDATION_ERROR
            PAYMENT_DECLINED,402,PAYMENT_DECLINED
            PRODUCT_NOT_FOUND_GET,404,PRODUCT_NOT_FOUND
            PRODUCT_NOT_FOUND_ORDER,404,PRODUCT_NOT_FOUND
            COUPON_NOT_FOUND_GET,404,COUPON_NOT_FOUND
            COUPON_NOT_FOUND_ORDER,404,COUPON_NOT_FOUND
            ORDER_NOT_FOUND_GET,404,ORDER_NOT_FOUND
            ORDER_NOT_FOUND_PAY,404,ORDER_NOT_FOUND
            ORDER_NOT_FOUND_CANCEL,404,ORDER_NOT_FOUND
            ORDER_NOT_FOUND_SHIP,404,ORDER_NOT_FOUND
            ORDER_NOT_FOUND_DELIVER,404,ORDER_NOT_FOUND
            INSUFFICIENT_STOCK,409,INSUFFICIENT_STOCK
            COUPON_NOT_APPLICABLE,409,COUPON_NOT_APPLICABLE
            COUPON_EXHAUSTED,409,COUPON_EXHAUSTED
            DUPLICATE_COUPON_CODE,409,DUPLICATE_COUPON_CODE
            INVALID_STATE_SHIP,409,INVALID_STATE
            INVALID_STATE_DELIVER,409,INVALID_STATE
            INVALID_STATE_CANCEL,409,INVALID_STATE
            INVALID_STATE_PAY,409,INVALID_STATE
            IDEMPOTENCY_IN_PROGRESS,409,IDEMPOTENCY_IN_PROGRESS
            IDEMPOTENCY_KEY_MISMATCH,422,IDEMPOTENCY_KEY_MISMATCH
            GATEWAY_5XX,503,PAYMENT_GATEWAY_UNAVAILABLE
            GATEWAY_DOWN,503,PAYMENT_GATEWAY_UNAVAILABLE
            GATEWAY_REFUND_5XX,503,PAYMENT_GATEWAY_UNAVAILABLE
            """;

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(textBlock = SCENARIOS)
    @DisplayName("R11.1 모든 오류 응답의 Content-Type 은 application/problem+json 이다")
    void r11_1_contentType_isProblemJson(String scenario, int expectedStatus, String expectedCode) {
        ResponseEntity<String> r = trigger(scenario);

        assertThat(statusOf(r)).as("%s body=%s", scenario, r.getBody()).isEqualTo(expectedStatus);
        MediaType contentType = r.getHeaders().getContentType();
        assertThat(contentType).as("Content-Type").isNotNull();
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("problem+json");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(textBlock = SCENARIOS)
    @DisplayName("R11.2 오류 본문에는 type, title, status, detail, code 가 있고 status 가 HTTP 상태와 같다")
    void r11_2_body_hasRequiredFields(String scenario, int expectedStatus, String expectedCode) {
        ResponseEntity<String> r = trigger(scenario);

        JsonNode body = json(r);
        assertThat(body.path("type").asText()).as("type").isNotBlank();
        assertThat(URI.create(body.get("type").asText())).isNotNull();
        assertThat(body.path("title").asText()).as("title").isNotBlank();
        assertThat(body.path("status").isInt()).as("status 는 정수").isTrue();
        assertThat(body.get("status").asInt()).isEqualTo(statusOf(r)).isEqualTo(expectedStatus);
        assertThat(body.path("detail").asText()).as("detail").isNotBlank();
        assertThat(body.path("code").asText()).as("code").isNotBlank();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(textBlock = SCENARIOS)
    @DisplayName("R11.3 code 는 상태별로 정의된 값 중 해당 오류의 값이다")
    void r11_3_code_matchesTable(String scenario, int expectedStatus, String expectedCode) {
        ResponseEntity<String> r = trigger(scenario);

        String code = json(r).path("code").asText();
        assertThat(code).isEqualTo(expectedCode);
        assertThat(SPEC_CODES).contains(code);
    }

    @Test
    @DisplayName("R11.1 클라이언트가 Accept: application/json 만 보내도 오류 Content-Type 은 application/problem+json")
    void r11_1_acceptApplicationJson_stillProblemJson() {
        ResponseEntity<String> r = exchange(HttpMethod.GET, "/api/products/987654321", null, "Accept", "application/json");

        assertThat(statusOf(r)).isEqualTo(404);
        assertThat(r.getHeaders().getContentType()).isNotNull();
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
    }

    @Test
    @DisplayName("R11.1 클라이언트가 Accept: application/problem+json 을 보내면 그대로 problem+json")
    void r11_1_acceptProblemJson_returnsProblemJson() {
        ResponseEntity<String> r = exchange(HttpMethod.GET, "/api/orders/987654321", null, "Accept", "application/problem+json");

        assertProblem(r, 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.1 JSON 파싱 실패(400)도 problem+json 이며 내부 예외 메시지(스택·클래스명)를 노출하지 않는다")
    void r11_1_jsonParseFailure_doesNotLeakInternals() {
        ResponseEntity<String> r = post("/api/products", "{\"name\":");

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(r.getBody()).doesNotContain("com.fasterxml").doesNotContain("at com.example").doesNotContain("Exception in");
    }

    @Test
    @DisplayName("R11.3 성공 응답에는 problem 필드(code)가 섞이지 않는다")
    void r11_3_successResponse_isNotProblem() {
        ResponseEntity<String> r = post("/api/products", Map.of("name", "ok", "price", 1000, "stock", 1));

        assertThat(statusOf(r)).isEqualTo(201);
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(r.getHeaders().getContentType().getSubtype()).isEqualTo("json");
        assertThat(json(r).has("code")).isFalse();
    }
}
