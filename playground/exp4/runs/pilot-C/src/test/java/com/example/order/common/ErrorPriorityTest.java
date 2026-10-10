package com.example.order.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Response;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * C3. 한 요청에 오류가 여럿이면 400 -> 멱등 키(422·409) -> 404 -> 409 -> PG 결과(402·503) 순으로
 * 먼저 해당하는 것을 반환한다. 같은 단계 안의 409 는 재고 -> 쿠폰 순이다.
 */
class ErrorPriorityTest extends IntegrationTestBase {

    private static final long MISSING = 999_999_999L;

    private Map<String, Object> pastCouponBody() {
        return couponBody(uniqueCode(), "FIXED", 100);
    }

    // ------------------------------------------------------------------ 400 이 최우선

    @Test
    @DisplayName("C3 헤더 오류(Idempotency-Key 누락) + 없는 상품 -> 400")
    void validationBeatsNotFound_headerCase() {
        ResponseEntity<JsonNode> res = post("/api/orders", map("items", items(MISSING, 1)), "X-User-Id", uniqueUser());

        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 본문 오류(quantity 0) + 없는 상품·없는 쿠폰 -> 400")
    void validationBeatsNotFound_bodyCase() {
        ResponseEntity<JsonNode> res = placeOrder(uniqueUser(), uniqueKey(), "NOSUCHCOUPON",
                items(MISSING, 0));

        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 이미 쓴 키 + 다른 본문이지만 본문이 검증 오류이면 -> 400 (422 보다 먼저)")
    void validationBeatsIdempotencyMismatch() {
        long p = newProduct(1000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        assertStatus(placeOrder(user, key, null, items(p, 1)), 201);

        assertProblem(placeOrder(user, key, null, items(p, 0)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 결제: cardToken 공백(400) + 없는 주문(404) -> 400")
    void validationBeatsNotFound_payCase() {
        ResponseEntity<JsonNode> res = pay(MISSING, uniqueKey(), "  ");

        assertProblem(res, 400, "VALIDATION_ERROR");
        assertThat(PG.requests()).isEmpty();
    }

    // ------------------------------------------------------------------ 멱등 키(422·409) -> 404

    @Test
    @DisplayName("C3 같은 키 + 다른 요청(없는 상품) -> 404 가 아니라 422")
    void idempotencyMismatchBeatsNotFound() {
        long p = newProduct(1000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        assertStatus(placeOrder(user, key, null, items(p, 1)), 201);

        ResponseEntity<JsonNode> res = placeOrder(user, key, null, items(MISSING, 1));

        assertProblem(res, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 같은 키 + 다른 요청(없는 쿠폰) -> 404 가 아니라 422")
    void idempotencyMismatchBeatsCouponNotFound() {
        long p = newProduct(1000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        assertStatus(placeOrder(user, key, null, items(p, 1)), 201);

        assertProblem(placeOrder(user, key, "NOSUCHCOUPON", items(p, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 결제: 같은 키를 다른 주문에 재사용 + 그 주문은 이미 취소됨(409) -> 422")
    void idempotencyMismatchBeatsInvalidState_payCase() {
        long p = newProduct(1000, 5);
        long first = newOrder(p, 1).get("id").asLong();
        long second = newOrder(p, 1).get("id").asLong();
        String key = uniqueKey();
        assertStatus(pay(first, key, "tok_ok"), 200);
        assertStatus(cancel(second), 200);

        assertProblem(pay(second, key, "tok_ok"), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 결제: 같은 키를 다른 주문에 재사용 + 그 주문이 없음(404) -> 422")
    void idempotencyMismatchBeatsNotFound_payCase() {
        long first = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        String key = uniqueKey();
        assertStatus(pay(first, key, "tok_ok"), 200);

        assertProblem(pay(MISSING, key, "tok_ok"), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    // ------------------------------------------------------------------ 404 -> 409

    @Test
    @DisplayName("C3 없는 상품 + 다른 항목 재고 부족 -> 404 (재고 부족이 앞서 있어도)")
    void notFoundBeatsInsufficientStock_missingLast() {
        long scarce = newProduct(1000, 1);

        ResponseEntity<JsonNode> res = placeOrder(uniqueUser(), uniqueKey(), null, items(scarce, 5, MISSING, 1));

        assertProblem(res, 404, "PRODUCT_NOT_FOUND");
        assertStock(scarce, 1, 0);
    }

    @Test
    @DisplayName("C3 없는 상품이 먼저 있고 재고 부족 항목이 뒤에 있어도 -> 404")
    void notFoundBeatsInsufficientStock_missingFirst() {
        long scarce = newProduct(1000, 1);

        ResponseEntity<JsonNode> res = placeOrder(uniqueUser(), uniqueKey(), null, items(MISSING, 1, scarce, 5));

        assertProblem(res, 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 없는 쿠폰 + 재고 부족 -> 404 COUPON_NOT_FOUND")
    void couponNotFoundBeatsInsufficientStock() {
        long scarce = newProduct(1000, 1);

        assertProblem(placeOrder(uniqueUser(), "NOSUCHCOUPON", scarce, 5), 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 없는 쿠폰 + 없는 상품 -> 404 (둘 중 하나, 어느 쪽이든 404)")
    void bothMissingIsStill404() {
        ResponseEntity<JsonNode> res = placeOrder(uniqueUser(), "NOSUCHCOUPON", MISSING, 1);

        assertThat(code(res)).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isIn("PRODUCT_NOT_FOUND", "COUPON_NOT_FOUND");
    }

    // ------------------------------------------------------------------ 409 안: 재고 -> 쿠폰

    @Test
    @DisplayName("C3 재고 부족 + 쿠폰 부적용(최소 금액 미달) -> INSUFFICIENT_STOCK")
    void insufficientStockBeatsCouponNotApplicable() {
        Map<String, Object> body = pastCouponBody();
        body.put("minOrderAmount", 1_000_000_000L);
        String code = createCoupon(body).get("code").asText();
        long scarce = newProduct(1000, 1);

        assertProblem(placeOrder(uniqueUser(), code, scarce, 5), 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 재고 부족 + 쿠폰 소진 -> INSUFFICIENT_STOCK")
    void insufficientStockBeatsCouponExhausted() {
        String code = newCoupon("FIXED", 100, 1);
        long plenty = newProduct(1000, 10);
        newOrder(uniqueUser(), code, items(plenty, 1));
        long scarce = newProduct(1000, 1);

        assertProblem(placeOrder(uniqueUser(), code, scarce, 5), 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 재고 부족 + 같은 사용자가 쿠폰 사용 중 -> INSUFFICIENT_STOCK")
    void insufficientStockBeatsCouponInUse() {
        String code = newCoupon("FIXED", 100, 5);
        long plenty = newProduct(1000, 10);
        String user = uniqueUser();
        newOrder(user, code, items(plenty, 1));
        long scarce = newProduct(1000, 1);

        assertProblem(placeOrder(user, code, scarce, 5), 409, "INSUFFICIENT_STOCK");
    }

    // ------------------------------------------------------------------ 409 -> PG 결과

    @Test
    @DisplayName("C3 쿠폰 부적용은 주문 생성 시점에 걸리므로 PG 는 호출되지 않는다")
    void couponNotApplicableHappensBeforeAnyGatewayCall() {
        Map<String, Object> body = pastCouponBody();
        body.put("minOrderAmount", 1_000_000_000L);
        String code = createCoupon(body).get("code").asText();

        assertProblem(placeOrder(uniqueUser(), code, newProduct(1000, 5), 1), 409, "COUPON_NOT_APPLICABLE");

        assertThat(PG.requests()).isEmpty();
    }

    @Test
    @DisplayName("C3 결제: 주문이 PENDING 이 아니면(409) PG 가 장애여도 503 이 아니라 409, PG 호출 없음")
    void invalidStateBeatsGatewayOutage() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        assertStatus(cancel(orderId), 200);
        PG.respondWith(r -> Response.status(500));

        assertProblem(pay(orderId), 409, "INVALID_STATE");

        assertThat(PG.requests()).isEmpty();
    }

    @Test
    @DisplayName("C3 결제: 없는 주문(404)이면 PG 가 장애여도 404, PG 호출 없음")
    void notFoundBeatsGatewayOutage() {
        PG.respondWith(r -> Response.status(500));

        assertProblem(pay(MISSING), 404, "ORDER_NOT_FOUND");

        assertThat(PG.requests()).isEmpty();
    }

    @Test
    @DisplayName("C3 취소: 이미 SHIPPED(409)이면 PG 가 장애여도 503 이 아니라 409")
    void invalidStateBeatsGatewayOutage_cancelCase() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        payOk(orderId);
        assertStatus(ship(orderId), 200);
        PG.respondWith(r -> Response.status(500));

        assertProblem(cancel(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("C3 PG 가 거절(402)이면 PG 결과 단계에서 반환된다 (검증·상태가 모두 통과한 요청)")
    void gatewayDeclineIsLastStage() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();

        assertProblem(pay(orderId, uniqueKey(), "decline_card"), 402, "PAYMENT_DECLINED");
    }
}
