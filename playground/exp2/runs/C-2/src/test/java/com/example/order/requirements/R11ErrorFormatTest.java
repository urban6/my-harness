package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** R11. 에러 포맷 (RFC 9457 Problem Details, code 값 전수) */
class R11ErrorFormatTest extends AbstractIntegrationTest {

    /** 어느 케이스가 실패했는지 알 수 있도록 라벨을 붙여 단언한다. */
    private static void expect(String label, ApiResponse r, int status, String code) {
        try {
            assertProblem(r, status, code);
        } catch (AssertionError e) {
            throw new AssertionError("[" + label + "] " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 400

    @Test
    @DisplayName("R11.3 400 VALIDATION_ERROR: 본문·헤더·쿼리·경로 변수 검증 실패")
    void r11_3_400_validationErrors() {
        long productId = newProduct(1_000, 5);
        long orderId = placeOrderOk(productId, 1).id();

        expect("본문 검증(상품 price 0)", createProduct("x", 0, 1), 400, "VALIDATION_ERROR");
        expect("본문 검증(쿠폰 code 소문자)", postCoupon(couponJson("abcd", "FIXED", 1, 0, null, 1)), 400, "VALIDATION_ERROR");
        expect("본문 검증(items 0개)", postOrder(uniqueUser(), uniqueKey(), "{\"items\":[]}"), 400, "VALIDATION_ERROR");
        expect("헤더(X-User-Id 누락)", postOrder(null, uniqueKey(), orderJson(null, line(productId, 1))), 400, "VALIDATION_ERROR");
        expect("헤더(Idempotency-Key 누락)", postOrder(uniqueUser(), null, orderJson(null, line(productId, 1))), 400, "VALIDATION_ERROR");
        expect("헤더(결제 Idempotency-Key 누락)", postPay(orderId, null, "{\"cardToken\":\"t\"}"), 400, "VALIDATION_ERROR");
        expect("본문(결제 cardToken 공백)", postPay(orderId, uniqueKey(), "{\"cardToken\":\" \"}"), 400, "VALIDATION_ERROR");
        expect("쿼리(size=0)", listOrders("size=0"), 400, "VALIDATION_ERROR");
        expect("쿼리(size=101)", listOrders("size=101"), 400, "VALIDATION_ERROR");
        expect("쿼리(status=BOGUS)", listOrders("status=BOGUS"), 400, "VALIDATION_ERROR");
        expect("쿼리(cursor 오류)", listOrders("cursor=not-a-cursor!!"), 400, "VALIDATION_ERROR");
        expect("경로(상품 id)", get("/api/products/abc"), 400, "VALIDATION_ERROR");
        expect("경로(주문 id)", get("/api/orders/abc"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.1 + R11.3 요청 본문 JSON 파싱 실패(400)도 application/problem+json VALIDATION_ERROR이다 (모든 본문 엔드포인트)")
    void r11_1_malformedJson_returns400Problem() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();
        String[] malformed = {"{\"name\":", "", "not json", "{}garbage", "[]", "\"text\"", "{\"a\":1,}"};

        for (String body : malformed) {
            expect("상품 본문 '" + body + "'", postProduct(body), 400, "VALIDATION_ERROR");
            expect("쿠폰 본문 '" + body + "'", postCoupon(body), 400, "VALIDATION_ERROR");
            expect("주문 본문 '" + body + "'", postOrder(uniqueUser(), uniqueKey(), body), 400, "VALIDATION_ERROR");
            expect("결제 본문 '" + body + "'", postPay(orderId, uniqueKey(), body), 400, "VALIDATION_ERROR");
        }
    }

    @Test
    @DisplayName("R11.1 JSON 타입 불일치(숫자 필드에 문자열·소수)도 400 VALIDATION_ERROR이다")
    void r11_1_typeMismatch_returns400Problem() {
        expect("price 문자열", postProduct("{\"name\":\"a\",\"price\":\"100\",\"stock\":1}"), 400, "VALIDATION_ERROR");
        expect("price 소수", postProduct("{\"name\":\"a\",\"price\":100.5,\"stock\":1}"), 400, "VALIDATION_ERROR");
        expect("name 숫자", postProduct("{\"name\":123,\"price\":100,\"stock\":1}"), 400, "VALIDATION_ERROR");
        expect("items 객체", postOrder(uniqueUser(), uniqueKey(), "{\"items\":{}}"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.1 본문이 아예 없는 POST(주문 생성·결제)도 400 problem+json이다")
    void r11_1_missingBody_returns400Problem() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();

        expect("주문 생성 본문 없음", post("/api/orders", headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey()), null),
                400, "VALIDATION_ERROR");
        expect("결제 본문 없음", post("/api/orders/" + orderId + "/pay", headers("Idempotency-Key", uniqueKey()), null),
                400, "VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------ 402 / 404 / 409 / 422 / 503

    @Test
    @DisplayName("R11.3 402 PAYMENT_DECLINED")
    void r11_3_402_paymentDeclined() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();
        PG.decline();

        expect("거절", pay(orderId), 402, "PAYMENT_DECLINED");
    }

    @Test
    @DisplayName("R11.3 404 PRODUCT_NOT_FOUND · COUPON_NOT_FOUND · ORDER_NOT_FOUND")
    void r11_3_404_codes() {
        long productId = newProduct(1_000, 5);

        expect("상품 조회", getProduct(987_654_321L), 404, "PRODUCT_NOT_FOUND");
        expect("주문 항목의 상품", placeOrder(null, line(987_654_321L, 1)), 404, "PRODUCT_NOT_FOUND");
        expect("쿠폰 조회", getCoupon("NOSUCH55"), 404, "COUPON_NOT_FOUND");
        expect("주문의 쿠폰", placeOrder("NOSUCH55", line(productId, 1)), 404, "COUPON_NOT_FOUND");
        expect("주문 조회", getOrder(987_654_321L), 404, "ORDER_NOT_FOUND");
        expect("결제", pay(987_654_321L), 404, "ORDER_NOT_FOUND");
        expect("취소", cancel(987_654_321L), 404, "ORDER_NOT_FOUND");
        expect("배송", ship(987_654_321L), 404, "ORDER_NOT_FOUND");
        expect("배송 완료", deliver(987_654_321L), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 409 INSUFFICIENT_STOCK · COUPON_NOT_APPLICABLE · COUPON_EXHAUSTED · DUPLICATE_COUPON_CODE · INVALID_STATE")
    void r11_3_409_codes() {
        long productId = newProduct(1_000, 2);
        String minCoupon = newCoupon("FIXED", 100, 1_000_000, null, 5);
        String oneUse = newCoupon("FIXED", 100, 0, null, 1);
        assertThat(placeOrder(oneUse, line(newProduct(1_000, 5), 1)).status()).isEqualTo(201);
        String existing = newCoupon("FIXED", 100, 0, null, 1);

        expect("재고 부족", placeOrder(null, line(productId, 3)), 409, "INSUFFICIENT_STOCK");
        expect("쿠폰 적용 불가", placeOrder(minCoupon, line(productId, 1)), 409, "COUPON_NOT_APPLICABLE");
        expect("쿠폰 소진", placeOrder(oneUse, line(productId, 1)), 409, "COUPON_EXHAUSTED");
        expect("쿠폰 코드 중복", postCoupon(couponJson(existing, "FIXED", 100, 0, null, 1)), 409, "DUPLICATE_COUPON_CODE");
        expect("배송(결제 전)", ship(placeOrderOk(productId, 1).id()), 409, "INVALID_STATE");
        expect("결제(취소된 주문)", pay(newOrderInStatus("CANCELLED")), 409, "INVALID_STATE");
        expect("취소(배송 완료)", cancel(newOrderInStatus("DELIVERED")), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R11.3 409 IDEMPOTENCY_IN_PROGRESS (같은 키 처리 중)")
    void r11_3_409_idempotencyInProgress() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();
        String key = uniqueKey();
        PG.delayPayment(1_500);
        CompletableFuture<ApiResponse> first = CompletableFuture.supplyAsync(() -> pay(orderId, key, "tok"));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> PG.paymentCallCount() == 1);

        ApiResponse second = pay(orderId, key, "tok");

        expect("처리 중인 키", second, 409, "IDEMPOTENCY_IN_PROGRESS");
        assertThat(first.join().status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R11.3 422 IDEMPOTENCY_KEY_MISMATCH")
    void r11_3_422_idempotencyKeyMismatch() {
        long productId = newProduct(1_000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        postOrder(user, key, orderJson(null, line(productId, 1)));

        expect("키 재사용(다른 본문)", postOrder(user, key, orderJson(null, line(productId, 2))), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("R11.3 503 PAYMENT_GATEWAY_UNAVAILABLE (결제 5xx · 환불 5xx)")
    void r11_3_503_paymentGatewayUnavailable() {
        long payOrder = placeOrderOk(newProduct(1_000, 5), 1).id();
        PG.http500();
        expect("결제 PG 5xx", pay(payOrder), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        PG.approve();
        long paid = newOrderInStatus("PAID");
        PG.refund500();
        expect("환불 PG 5xx", cancel(paid), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
    }

    // ------------------------------------------------------------------ 형식

    @Test
    @DisplayName("R11.2 필드는 type(URI)·title·status(HTTP 상태와 같음)·detail·code를 모두 갖는다")
    void r11_2_requiredFields() {
        ApiResponse r = getOrder(987_654_321L);

        assertProblem(r, 404, "ORDER_NOT_FOUND");
        assertThat(URI.create(r.text("type")).isAbsolute()).isTrue();
        assertThat(r.json().get("title").isTextual()).isTrue();
        assertThat(r.json().get("status").isInt()).isTrue();
        assertThat(r.json().get("detail").isTextual()).isTrue();
        assertThat(r.json().get("code").isTextual()).isTrue();
    }

    @Test
    @DisplayName("R11.2 서로 다른 code는 서로 다른 type을 갖고, 같은 code는 항상 같은 type을 갖는다")
    void r11_2_typeIsStablePerCode() {
        ApiResponse a1 = getOrder(987_654_321L);
        ApiResponse a2 = getOrder(987_654_322L);
        ApiResponse b = getProduct(987_654_321L);

        assertThat(a1.text("type")).isEqualTo(a2.text("type"));
        assertThat(a1.text("type")).isNotEqualTo(b.text("type"));
    }

    @Test
    @DisplayName("R11.3 400 응답의 errors[]에는 위반 필드가 보고된다")
    void r11_3_400_reportsFieldErrors() {
        ApiResponse r = createProduct("x", 0, -1);

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(r.json().get("errors")).isNotEmpty();
        assertThat(r.json().get("errors").findValuesAsText("field")).contains("price", "stock");
    }

    @ParameterizedTest(name = "R11.1 Accept: {0}에서도 problem+json이다")
    @ValueSource(strings = {"application/json", "*/*", "application/problem+json", "text/html"})
    @DisplayName("R11.1 클라이언트 Accept와 무관하게 오류 응답의 Content-Type은 application/problem+json이다")
    void r11_1_contentTypeIndependentOfAccept(String accept) {
        ApiResponse r = send(org.springframework.http.HttpMethod.GET, "/api/orders/987654321", headers("Accept", accept), null);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.code()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.1 오류 응답 본문에 스택트레이스·SQL 같은 내부 정보가 없다")
    void r11_1_noInternalDetailsLeaked() {
        ApiResponse r = postProduct("{\"name\":");

        assertThat(r.body()).doesNotContain("Exception", "at com.", "org.springframework", "SELECT ", "jackson");
    }
}
