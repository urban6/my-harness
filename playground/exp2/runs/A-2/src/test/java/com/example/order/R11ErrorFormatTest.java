package com.example.order;

import com.example.order.support.Api.Resp;
import com.example.order.support.FakePaymentGateway.Behavior;
import com.example.order.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R11 에러 포맷")
class R11ErrorFormatTest extends IntegrationTest {

    @Test
    @DisplayName("R11.1 요청 본문 JSON 파싱 실패도 400 Problem Details")
    void malformedJson() {
        Resp products = api.postRaw("/api/products", "{\"name\": \"x\", ");
        Resp coupons = api.postRaw("/api/coupons", "not json");
        Resp pay = api.postRaw("/api/orders/1/pay", "[", "Idempotency-Key", newKey());

        assertProblem(products, 400, "VALIDATION_ERROR");
        assertProblem(coupons, 400, "VALIDATION_ERROR");
        assertProblem(pay, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.1·R11.2 Content-Type은 application/problem+json, 필드는 type·title·status·detail·code")
    void problemShape() {
        Resp resp = api.get("/api/products/123456");

        assertThat(resp.header("Content-Type")).startsWith("application/problem+json");
        assertThat(resp.json().path("type").asText()).isNotBlank();
        assertThat(resp.json().path("title").asText()).isNotBlank();
        assertThat(resp.json().path("status").asInt()).isEqualTo(404);
        assertThat(resp.json().path("detail").asText()).isNotBlank();
        assertThat(resp.json().path("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R11.3 상태별 code")
    void codesPerStatus() {
        long p = createProduct(1000, 1);
        createCoupon(couponBody("ERRS", "FIXED", 100));
        String key = newKey();
        long orderId = createOrder("u1", key, orderBody("ERRS", p, 1)).id();

        assertProblem(api.post("/api/products", Map.of("name", "")), 400, "VALIDATION_ERROR");
        assertProblem(api.get("/api/products/999"), 404, "PRODUCT_NOT_FOUND");
        assertProblem(api.get("/api/coupons/NOPE"), 404, "COUPON_NOT_FOUND");
        assertProblem(api.get("/api/orders/999"), 404, "ORDER_NOT_FOUND");
        assertProblem(placeOrder("u2", null, p, 1), 409, "INSUFFICIENT_STOCK");
        assertProblem(api.post("/api/coupons", couponBody("ERRS", "FIXED", 1)), 409, "DUPLICATE_COUPON_CODE");
        assertProblem(api.post("/api/orders/" + orderId + "/ship", null), 409, "INVALID_STATE");
        assertProblem(createOrder("u1", key, orderBody(null, p, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");

        PG.paymentBehavior(Behavior.SERVER_ERROR);
        assertProblem(pay(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        PG.paymentBehavior(Behavior.DECLINE);
        assertProblem(pay(orderId), 402, "PAYMENT_DECLINED");

        long q = createProduct(1000, 10);
        Map<String, Object> limited = couponBody("ONE1", "FIXED", 100);
        limited.put("totalQuantity", 1);
        createCoupon(limited);
        placeOrderOk("a", "ONE1", q, 1);
        assertProblem(placeOrder("a", "ERRS", q, 1, p, 5), 409, "INSUFFICIENT_STOCK");
        assertProblem(placeOrder("b", "ONE1", q, 1), 409, "COUPON_EXHAUSTED");
        assertProblem(placeOrder("a", "ONE1", q, 1), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("C3 같은 단계의 409는 재고가 쿠폰보다 먼저, 404는 409보다 먼저")
    void precedence() {
        long p = createProduct(1000, 1);
        Map<String, Object> limited = couponBody("GONE", "FIXED", 100);
        limited.put("totalQuantity", 1);
        createCoupon(limited);
        placeOrderOk("a", "GONE", createProduct(1000, 10), 1);

        assertProblem(placeOrder("b", "GONE", p, 2), 409, "INSUFFICIENT_STOCK");
        assertProblem(placeOrder("b", "MISSING", p, 2), 404, "COUPON_NOT_FOUND");
        assertProblem(placeOrder("b", "GONE", p, 2, 999_999, 1), 404, "PRODUCT_NOT_FOUND");
    }
}
