package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** C3. 오류 우선순위: 400 -> 멱등 키(422·409) -> 404 -> 409(재고 -> 쿠폰) -> PG 결과(402·503). */
class C03ErrorPriorityTest extends AbstractIntegrationTest {

    private static final long MISSING_PRODUCT = 999_999_999L;

    // ------------------------------------------------------------ 주문 생성

    @Test
    @DisplayName("C3 400과 404가 동시에 해당하면 400 (quantity 0 + 없는 상품)")
    void c3_create_validationBeatsNotFound_body() {
        ApiResponse r = postOrder(uniqueUser(), uniqueKey(), orderJson("NOSUCH77", line(MISSING_PRODUCT, 0)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400과 404가 동시에 해당하면 400 (X-User-Id 누락 + 없는 상품·쿠폰)")
    void c3_create_validationBeatsNotFound_header() {
        ApiResponse r = postOrder(null, uniqueKey(), orderJson("NOSUCH77", line(MISSING_PRODUCT, 1)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400과 409가 동시에 해당하면 400 (productId 중복 + 재고 부족)")
    void c3_create_validationBeatsConflict() {
        long productId = newProduct(1_000, 1);

        ApiResponse r = postOrder(uniqueUser(), uniqueKey(), orderJson(null, line(productId, 5), line(productId, 5)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400과 멱등 키 불일치가 동시에 해당하면 400 (이미 쓴 키 + 잘못된 본문)")
    void c3_create_validationBeatsIdempotencyMismatch() {
        long productId = newProduct(1_000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(postOrder(user, key, orderJson(null, line(productId, 1))).status()).isEqualTo(201);

        ApiResponse r = postOrder(user, key, orderJson(null, line(productId, 0)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 멱등 키 422가 404보다 먼저다 (같은 키 + 다른 본문(없는 상품))")
    void c3_create_idempotencyMismatchBeatsNotFound() {
        long productId = newProduct(1_000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(postOrder(user, key, orderJson(null, line(productId, 1))).status()).isEqualTo(201);

        ApiResponse r = postOrder(user, key, orderJson(null, line(MISSING_PRODUCT, 1)));

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 멱등 키 422가 409보다 먼저다 (같은 키 + 다른 본문(재고 초과))")
    void c3_create_idempotencyMismatchBeatsConflict() {
        long productId = newProduct(1_000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        assertThat(postOrder(user, key, orderJson(null, line(productId, 1))).status()).isEqualTo(201);

        ApiResponse r = postOrder(user, key, orderJson(null, line(productId, 50)));

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 멱등 재생이 409보다 먼저다 (재고가 소진된 뒤에도 같은 요청은 최초 201을 돌려준다)")
    void c3_create_replayBeatsConflict() {
        long productId = newProduct(1_000, 1);
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderJson(null, line(productId, 1));
        ApiResponse first = postOrder(user, key, body);
        assertThat(first.status()).isEqualTo(201);
        assertThat(available(productId)).isZero();

        ApiResponse replay = postOrder(user, key, body);

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
    }

    @Test
    @DisplayName("C3 404와 409가 동시에 해당하면 404 (없는 상품 + 다른 항목 재고 부족)")
    void c3_create_notFoundBeatsConflict_product() {
        long scarce = newProduct(1_000, 1);

        ApiResponse r = placeOrder(null, line(scarce, 5), line(MISSING_PRODUCT, 1));

        assertProblem(r, 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 404와 409가 동시에 해당하면 404 (없는 쿠폰 + 재고 부족)")
    void c3_create_notFoundBeatsConflict_coupon() {
        long scarce = newProduct(1_000, 1);

        ApiResponse r = placeOrder("NOSUCH66", line(scarce, 5));

        assertProblem(r, 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 재고 409와 쿠폰 409가 동시에 해당하면 INSUFFICIENT_STOCK (쿠폰 적용 불가)")
    void c3_create_stockConflictBeatsCouponNotApplicable() {
        long scarce = newProduct(1_000, 1);
        String coupon = newCoupon("FIXED", 100, 1_000_000, null, 5); // minOrderAmount 미달

        ApiResponse r = placeOrder(coupon, line(scarce, 5));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
        assertThat(usedCount(coupon)).isZero();
    }

    @Test
    @DisplayName("C3 재고 409와 쿠폰 409가 동시에 해당하면 INSUFFICIENT_STOCK (쿠폰 소진)")
    void c3_create_stockConflictBeatsCouponExhausted() {
        long plenty = newProduct(1_000, 10);
        long scarce = newProduct(1_000, 1);
        String coupon = newCoupon("FIXED", 100, 0, null, 1);
        assertThat(placeOrder(coupon, line(plenty, 1)).status()).isEqualTo(201);

        ApiResponse r = placeOrder(coupon, line(scarce, 5));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
    }

    // ------------------------------------------------------------ 결제

    @Test
    @DisplayName("C3 결제: 400이 404보다 먼저다 (Idempotency-Key 누락 + 없는 주문)")
    void c3_pay_validationBeatsNotFound_header() {
        ApiResponse r = postPay(MISSING_PRODUCT, null, "{\"cardToken\":\"tok\"}");

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 결제: 400이 404보다 먼저다 (cardToken 공백 + 없는 주문)")
    void c3_pay_validationBeatsNotFound_body() {
        ApiResponse r = postPay(MISSING_PRODUCT, uniqueKey(), "{\"cardToken\":\"   \"}");

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(PG.paymentCallCount()).isZero();
    }

    @Test
    @DisplayName("C3 결제: 400이 409보다 먼저다 (공백 cardToken + 이미 취소된 주문)")
    void c3_pay_validationBeatsConflict() {
        long orderId = newOrderInStatus("CANCELLED");

        ApiResponse r = postPay(orderId, uniqueKey(), "{\"cardToken\":\"\"}");

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 결제: 멱등 키 422가 404보다 먼저다 (이미 쓴 키 + 없는 주문)")
    void c3_pay_idempotencyMismatchBeatsNotFound() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();
        String key = uniqueKey();
        assertThat(pay(orderId, key, "tok").status()).isEqualTo(200);

        ApiResponse r = pay(MISSING_PRODUCT, key, "tok");

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 결제: 멱등 키 422가 409보다 먼저다 (결제 완료 주문에 같은 키 + 다른 cardToken)")
    void c3_pay_idempotencyMismatchBeatsConflict() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();
        String key = uniqueKey();
        assertThat(pay(orderId, key, "tok").status()).isEqualTo(200);

        ApiResponse r = pay(orderId, key, "other-token");

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 결제: 멱등 재생이 409보다 먼저다 (결제 완료 후 같은 요청은 최초 200, 새 키는 409)")
    void c3_pay_replayBeatsConflict_newKeyGetsConflict() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();
        String key = uniqueKey();
        ApiResponse first = pay(orderId, key, "tok");

        ApiResponse replay = pay(orderId, key, "tok");
        ApiResponse fresh = pay(orderId, uniqueKey(), "tok");

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertProblem(fresh, 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("C3 결제: 404 없는 주문은 PG를 호출하지 않는다")
    void c3_pay_notFound_doesNotCallGateway() {
        ApiResponse r = pay(MISSING_PRODUCT, uniqueKey(), "tok");

        assertProblem(r, 404, "ORDER_NOT_FOUND");
        assertThat(PG.paymentCallCount()).isZero();
    }

    @Test
    @DisplayName("C3 결제: 409 INVALID_STATE가 PG 결과(503)보다 먼저다 (취소된 주문 + PG 5xx)")
    void c3_pay_conflictBeatsGatewayFailure() {
        long orderId = newOrderInStatus("CANCELLED");
        PG.reset();
        PG.http500();

        ApiResponse r = pay(orderId);

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCallCount()).isZero();
    }

    @Test
    @DisplayName("C3 결제: 409 INVALID_STATE가 PG 결과(402)보다 먼저다 (결제 완료 주문 + PG 거절)")
    void c3_pay_conflictBeatsDecline() {
        long orderId = newOrderInStatus("PAID");
        PG.reset();
        PG.decline();

        ApiResponse r = pay(orderId);

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCallCount()).isZero();
        assertThat(getOrder(orderId).text("status")).isEqualTo("PAID");
    }

    // ------------------------------------------------------------ 기타 엔드포인트

    @Test
    @DisplayName("C3 취소·배송: 404가 먼저고, 존재하면 상태 409이다")
    void c3_cancelShipDeliver_notFoundThenConflict() {
        assertProblem(cancel(MISSING_PRODUCT), 404, "ORDER_NOT_FOUND");
        assertProblem(ship(MISSING_PRODUCT), 404, "ORDER_NOT_FOUND");
        assertProblem(deliver(MISSING_PRODUCT), 404, "ORDER_NOT_FOUND");

        long pending = newOrderInStatus("PENDING_PAYMENT");
        assertProblem(ship(pending), 409, "INVALID_STATE");
        assertProblem(deliver(pending), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("C3 취소·배송: 숫자가 아닌 id의 400이 404보다 먼저다")
    void c3_cancelShipDeliver_validationFirst() {
        assertProblem(post("/api/orders/abc/cancel", null, null), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/abc/ship", null, null), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/abc/deliver", null, null), 400, "VALIDATION_ERROR");
    }
}
