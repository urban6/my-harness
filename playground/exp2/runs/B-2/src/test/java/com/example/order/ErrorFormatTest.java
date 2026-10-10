package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** R11. 에러 포맷 — 대표 오류마다 RFC 9457 형식과 code를 확인한다(다른 테스트도 assertProblem으로 함께 검증). */
class ErrorFormatTest extends IntegrationTestSupport {

    @Test
    void malformedJson_isProblemDetail400() {
        Res res = post("/api/products", "{\"name\": \"a\", ", Map.of());

        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    void wrongJsonType_isProblemDetail400() {
        assertProblem(post("/api/coupons", "[1,2,3]", Map.of()), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/products", "{\"name\":\"a\",\"price\":\"abc\",\"stock\":1}", Map.of()), 400, "VALIDATION_ERROR");
    }

    @Test
    void validationError_listsFieldErrors() {
        Res res = post("/api/products", Map.of("name", "", "price", 0, "stock", -1), Map.of());

        assertProblem(res, 400, "VALIDATION_ERROR");
        assertThat(res.body().get("errors")).hasSize(3);
    }

    @Test
    void invalidPathVariable_isProblemDetail400() {
        assertProblem(get("/api/orders/abc"), 400, "VALIDATION_ERROR");
    }

    @Test
    void everyDomainErrorCarriesTypeTitleStatusDetailCode() {
        long p = createProduct(1_000, 1);
        String code = createCoupon(Map.of());

        assertProblem(get("/api/products/999999999"), 404, "PRODUCT_NOT_FOUND");
        assertProblem(get("/api/coupons/NOPE1234"), 404, "COUPON_NOT_FOUND");
        assertProblem(get("/api/orders/999999999"), 404, "ORDER_NOT_FOUND");
        assertProblem(post("/api/coupons", couponBody(Map.of("code", code)), Map.of()), 409, "DUPLICATE_COUPON_CODE");
        assertProblem(createOrder(uniqueUser(), null, item(p, 2)), 409, "INSUFFICIENT_STOCK");

        String key = uniqueKey();
        String user = uniqueUser();
        postOrder(user, key, orderBody(null, item(p, 1)));
        assertProblem(postOrder(user, key, orderBody(code, item(p, 1))), 422, "IDEMPOTENCY_KEY_MISMATCH");

        long orderId = placeOrder(uniqueUser(), null, item(createProduct(1_000, 10), 1));
        PG.paymentMode(FakePaymentGateway.Mode.SERVER_ERROR);
        Res unavailable = pay(orderId, uniqueKey());
        assertProblem(unavailable, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(unavailable.body().get("type").asText()).isNotBlank();
        assertThat(unavailable.body().get("title").asText()).isNotBlank();

        PG.paymentMode(FakePaymentGateway.Mode.DECLINE);
        assertProblem(pay(orderId, uniqueKey()), 402, "PAYMENT_DECLINED");
        assertProblem(post("/api/orders/" + orderId + "/ship"), 409, "INVALID_STATE");
    }
}
