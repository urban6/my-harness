package com.example.order;

import static com.example.order.support.TestApi.coupon;
import static com.example.order.support.TestApi.item;
import static com.example.order.support.TestApi.newCouponCode;
import static com.example.order.support.TestApi.newKey;
import static com.example.order.support.TestApi.newUserId;
import static com.example.order.support.TestApi.orderBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Mode;
import com.example.order.support.IntegrationTest;
import com.example.order.support.TestApi.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R11 에러 포맷 · C3 오류 우선순위")
class ErrorFormatApiTest extends IntegrationTest {

    @Test
    @DisplayName("R11.1·R11.2 JSON 파싱 실패 → 400 Problem Details(type·title·status·detail·code)")
    void malformedJson_isProblemDetail() {
        Response response = api.post("/api/products", "{\"name\": ").assertProblem(400, "VALIDATION_ERROR");
        assertThat(response.body().get("type").asText()).isNotBlank();
        assertThat(response.body().get("title").asText()).isNotBlank();

        api.post("/api/coupons", "not json").assertProblem(400, "VALIDATION_ERROR");
        api.post("/api/products", "[]").assertProblem(400, "VALIDATION_ERROR");
        api.post("/api/products", Map.of("name", "x", "price", "abc", "stock", 1)).assertProblem(400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R11.3 상태코드별 code 값")
    void codesByStatus() {
        long productId = api.createProduct(1_000, 1);
        String code = api.createCoupon("FIXED", 100);

        api.get("/api/orders/abc").assertProblem(400, "VALIDATION_ERROR");
        api.get("/api/products/999999999").assertProblem(404, "PRODUCT_NOT_FOUND");
        api.get("/api/coupons/NOPE9999").assertProblem(404, "COUPON_NOT_FOUND");
        api.get("/api/orders/999999999").assertProblem(404, "ORDER_NOT_FOUND");
        api.post("/api/coupons", coupon(code, "FIXED", 1)).assertProblem(409, "DUPLICATE_COUPON_CODE");
        api.createOrder(newUserId(), null, List.of(item(productId, 2))).assertProblem(409, "INSUFFICIENT_STOCK");

        long orderId = api.createOrderId(newUserId(), productId, 1);
        PG.paymentMode(Mode.DECLINE);
        api.pay(orderId).assertProblem(402, "PAYMENT_DECLINED");
        api.pay(orderId).assertProblem(409, "INVALID_STATE");
    }

    @Test
    @DisplayName("C3 400이 404보다 먼저: 검증 오류 + 없는 상품 → 400")
    void validationBeforeNotFound() {
        api.createOrder(newUserId(), null, List.of(item(999_999_999L, 0))).assertProblem(400, "VALIDATION_ERROR");
        api.createOrder(newUserId(), "NOSUCH99", List.of()).assertProblem(400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400이 멱등 키 오류보다 먼저: 키 재사용 + 검증 오류 → 400")
    void validationBeforeIdempotency() {
        long productId = api.createProduct(1_000, 10);
        String user = newUserId();
        String key = newKey();
        api.createOrder(user, key, orderBody(null, List.of(item(productId, 1)))).assertStatus(201);

        api.createOrder(user, key, orderBody(null, List.of(item(productId, 0)))).assertProblem(400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 멱등 키(422)가 404보다 먼저")
    void idempotencyBeforeNotFound() {
        long productId = api.createProduct(1_000, 10);
        String user = newUserId();
        String key = newKey();
        api.createOrder(user, key, orderBody(null, List.of(item(productId, 1)))).assertStatus(201);

        api.createOrder(user, key, orderBody(null, List.of(item(999_999_999L, 1))))
                .assertProblem(422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 404가 409보다 먼저: 재고 부족 상품 + 없는 상품·쿠폰 → 404")
    void notFoundBeforeConflict() {
        long scarce = api.createProduct(1_000, 1);

        api.createOrder(newUserId(), null, List.of(item(scarce, 5), item(999_999_999L, 1)))
                .assertProblem(404, "PRODUCT_NOT_FOUND");
        api.createOrder(newUserId(), "NOSUCH77", List.of(item(scarce, 5)))
                .assertProblem(404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 같은 단계 409는 재고 → 쿠폰 순")
    void stockConflictBeforeCouponConflict() {
        long scarce = api.createProduct(1_000, 1);
        Map<String, Object> request = coupon(newCouponCode(), "FIXED", 100);
        request.put("minOrderAmount", 1_000_000);
        String notApplicable = api.createCoupon(request);

        api.createOrder(newUserId(), notApplicable, List.of(item(scarce, 5)))
                .assertProblem(409, "INSUFFICIENT_STOCK");
        api.createOrder(newUserId(), notApplicable, List.of(item(scarce, 1)))
                .assertProblem(409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("C3 409가 PG 결과보다 먼저: 이미 결제된 주문은 PG 장애여도 409")
    void conflictBeforeGateway() {
        long orderId = api.createOrderId(newUserId(), api.createProduct(1_000, 10), 1);
        api.pay(orderId).assertStatus(200);
        PG.paymentMode(Mode.SERVER_ERROR);

        api.pay(orderId).assertProblem(409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).hasSize(1);
    }
}
