package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** C3: 400 -> 멱등 키(422·409) -> 404 -> 409(재고 -> 쿠폰) -> PG(402·503). */
@DisplayName("C3 오류 우선순위")
class C3ErrorPriorityTest extends IntegrationTestBase {

    private long product(int stock) {
        return createProduct("상품", 1000, stock).get("id").asLong();
    }

    // ---------------- 400 이 먼저 ----------------

    @Test
    @DisplayName("C3 400 vs 404: quantity 위반 + 없는 상품 -> 400")
    void validationBeforeProductNotFound() {
        assertProblem(order("u1", null, 9999, 0), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400 vs 404: 헤더 누락 + 없는 상품/쿠폰 -> 400")
    void headerValidationBeforeNotFound() {
        ResponseEntity<JsonNode> res = post("/api/orders", orderBody("NOPE0001", 9999, 1), "Idempotency-Key", key());

        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400 vs 409: 중복 productId + 재고 부족 -> 400")
    void validationBeforeStockConflict() {
        long p = product(1);

        assertProblem(order("u1", null, p, 5, p, 5), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400 vs 404(결제): cardToken 공백 + 없는 주문 -> 400")
    void payValidationBeforeOrderNotFound() {
        ResponseEntity<JsonNode> res = post("/api/orders/9999/pay", Map.of("cardToken", " "),
                "Idempotency-Key", key());

        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400 vs 422: 이미 쓴 키라도 본문이 검증 위반이면 422 가 아니라 400")
    void validationBeforeIdempotencyMismatch() {
        long p = product(5);
        createOrder("u1", "used-key", orderBody(null, p, 1));

        assertProblem(createOrder("u1", "used-key", orderBody(null, p, 0)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400 vs 409: 쿠폰 중복 code + 본문 위반 -> 400")
    void couponValidationBeforeDuplicate() {
        createCoupon("PRIO0001", "FIXED", 100);
        Map<String, Object> body = couponBody("PRIO0001", "RATE", 1000);

        assertProblem(post("/api/coupons", body), 400, "VALIDATION_ERROR");
    }

    // ---------------- 멱등(422) 이 404·409 보다 먼저 ----------------

    @Test
    @DisplayName("C3 422 vs 404: 이미 쓴 키 + 다른 본문(없는 상품) -> 422")
    void idempotencyMismatchBeforeProductNotFound() {
        long p = product(5);
        createOrder("u1", "k-prio1", orderBody(null, p, 1));

        assertProblem(createOrder("u1", "k-prio1", orderBody(null, 9999, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 422 vs 404: 이미 쓴 키 + 다른 본문(없는 쿠폰) -> 422")
    void idempotencyMismatchBeforeCouponNotFound() {
        long p = product(5);
        createOrder("u1", "k-prio2", orderBody(null, p, 1));

        assertProblem(createOrder("u1", "k-prio2", orderBody("NOPE0001", p, 1)), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 422 vs 409: 이미 쓴 키 + 다른 본문(재고 초과) -> 422")
    void idempotencyMismatchBeforeStockConflict() {
        long p = product(5);
        createOrder("u1", "k-prio3", orderBody(null, p, 1));

        assertProblem(createOrder("u1", "k-prio3", orderBody(null, p, 50)), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 422 vs 404(결제): 다른 주문에 쓴 키를 없는 주문 결제에 재사용 -> 422")
    void payIdempotencyMismatchBeforeOrderNotFound() {
        long p = product(5);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        payOrder(id, "k-prio4", "tok");

        assertProblem(payOrder(9999, "k-prio4", "tok"), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 422 vs 409(결제): 이미 PAID 인 주문에 쓴 키로 다른 cardToken 재요청 -> 422 (409 INVALID_STATE 아님)")
    void payIdempotencyMismatchBeforeInvalidState() {
        long p = product(5);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        payOrder(id, "k-prio5", "tok-a");

        assertProblem(payOrder(id, "k-prio5", "tok-b"), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 재생 vs 409: 같은 키·같은 요청의 결제 재요청은 주문이 이미 PAID 여도 409 가 아니라 200 재생")
    void replayBeforeInvalidState() {
        long p = product(5);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        payOrder(id, "k-prio6", "tok");

        assertThat(payOrder(id, "k-prio6", "tok").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("C3 409 IN_PROGRESS vs 404/409: 같은 키 요청이 처리 중이면 그 오류가 우선")
    void inProgressBeforeDomainErrors() throws Exception {
        long p = product(5);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        PG.delay(1500);
        var inFlight = java.util.concurrent.CompletableFuture.supplyAsync(() -> payOrder(id, "k-prio7", "tok"));
        assertThat(awaitUntil(java.time.Instant.now().plusSeconds(5), () -> PG.payCallCount() >= 1)).isTrue();

        // 같은 키·같은 요청: 주문은 아직 PENDING_PAYMENT 지만 진행 중이므로 IN_PROGRESS
        assertProblem(payOrder(id, "k-prio7", "tok"), 409, "IDEMPOTENCY_IN_PROGRESS");
        // 같은 키·다른 요청: 422
        assertProblem(payOrder(id, "k-prio7", "other"), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(inFlight.get().getStatusCode().value()).isEqualTo(200);
    }

    // ---------------- 404 가 409 보다 먼저 ----------------

    @Test
    @DisplayName("C3 404 vs 409: 한 상품은 재고 부족, 다른 상품은 없음 -> 404")
    void notFoundBeforeInsufficientStock() {
        long p = product(1);

        assertProblem(order("u1", null, p, 5, 9999, 1), 404, "PRODUCT_NOT_FOUND");
        assertProblem(order("u1", null, 9999, 1, p, 5), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 404 vs 409: 재고 부족 + 없는 쿠폰 -> 404 COUPON_NOT_FOUND")
    void couponNotFoundBeforeInsufficientStock() {
        long p = product(1);

        assertProblem(order("u1", "NOPE0001", p, 5), 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 404 vs 409: 쿠폰 부적용 + 없는 상품 -> 404 PRODUCT_NOT_FOUND")
    void productNotFoundBeforeCouponConflict() {
        long p = product(5);
        Map<String, Object> c = couponBody("PRIO0002", "FIXED", 100);
        c.put("minOrderAmount", 1_000_000);
        createCoupon(c);

        assertProblem(order("u1", "PRIO0002", p, 1, 9999, 1), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 404 vs PG: PG 가 죽어 있어도 없는 주문 결제는 404")
    void orderNotFoundBeforePgFailure() {
        PG.fail5xx();

        assertProblem(payOrder(9999, key(), "tok"), 404, "ORDER_NOT_FOUND");
        assertThat(PG.payCallCount()).isZero();
    }

    // ---------------- 409 안에서 재고 -> 쿠폰 ----------------

    @Test
    @DisplayName("C3 409 재고 vs 쿠폰: 재고 부족 + 쿠폰 소진 -> INSUFFICIENT_STOCK")
    void stockBeforeCouponExhausted() {
        long p = product(1);
        Map<String, Object> c = couponBody("PRIO0003", "FIXED", 100);
        c.put("totalQuantity", 1);
        createCoupon(c);
        long other = product(5);
        orderOk("u0", "PRIO0003", other, 1);

        assertProblem(order("u1", "PRIO0003", p, 5), 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 409 재고 vs 쿠폰: 재고 부족 + 쿠폰 부적용(최소 금액 미달) -> INSUFFICIENT_STOCK")
    void stockBeforeCouponNotApplicable() {
        long p = product(1);
        Map<String, Object> c = couponBody("PRIO0004", "FIXED", 100);
        c.put("minOrderAmount", 1_000_000);
        createCoupon(c);

        assertProblem(order("u1", "PRIO0004", p, 5), 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 409 재고 vs 쿠폰: 재고 부족 + 같은 사용자 쿠폰 사용 중 -> INSUFFICIENT_STOCK")
    void stockBeforeCouponAlreadyUsed() {
        long p = product(5);
        createCoupon("PRIO0005", "FIXED", 100);
        orderOk("u1", "PRIO0005", p, 1);

        assertProblem(order("u1", "PRIO0005", p, 50), 409, "INSUFFICIENT_STOCK");
    }

    // ---------------- 409 가 PG 결과보다 먼저 ----------------

    @Test
    @DisplayName("C3 409 vs 503: 이미 취소된 주문 결제는 PG 가 죽어 있어도 409 INVALID_STATE, PG 미호출")
    void invalidStateBeforePgFailure() {
        long id = orderOk("u1", null, product(5), 1).get("id").asLong();
        cancel(id);
        PG.fail5xx();

        assertProblem(payOrder(id, key(), "tok"), 409, "INVALID_STATE");
        assertThat(PG.payCallCount()).isZero();
    }

    @Test
    @DisplayName("C3 409 vs 402: 이미 PAID 인 주문 결제는 PG 가 거절 모드여도 409 INVALID_STATE")
    void invalidStateBeforePgDecline() {
        long id = orderOk("u1", null, product(5), 1).get("id").asLong();
        payOk(id);
        PG.decline();

        assertProblem(payOrder(id, key(), "tok"), 409, "INVALID_STATE");
        assertThat(PG.payCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("C3 409 vs 503(환불): CANCELLED 주문 취소는 PG 와 무관하게 409 INVALID_STATE")
    void cancelInvalidStateBeforePg() {
        long id = orderOk("u1", null, product(5), 1).get("id").asLong();
        cancel(id);
        PG.fail5xx();

        assertProblem(cancel(id), 409, "INVALID_STATE");
        assertThat(PG.refundCallCount()).isZero();
    }
}
