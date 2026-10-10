package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Recorded;
import com.example.order.support.FakePaymentGateway.Response;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

/** R5. 결제 + 외부 PG 계약(결제 요청 부분). */
class OrderPaymentTest extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode pgBody(Recorded request) throws Exception {
        return JSON.readTree(request.body());
    }

    /** 주문·재고·쿠폰이 결제 시도 전 상태 그대로인지 확인한다. */
    private void assertUntouched(long orderId, long productId, int stock, int reserved) {
        JsonNode o = order(orderId);
        assertThat(o.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.get("paidAt").isNull()).isTrue();
        assertStock(productId, stock, reserved);
    }

    // ------------------------------------------------------------------ R5.4 승인

    @Test
    @DisplayName("R5.4 승인되면 200, status=PAID, paidAt 기록, 각 상품 stock·reserved 가 주문 수량만큼 줄어든다")
    void r5_4_approvedMarksPaidAndDecrementsStockAndReserved() {
        long a = newProduct(1000, 10);
        long b = newProduct(2000, 10);
        JsonNode created = newOrder(uniqueUser(), null, items(a, 3, b, 4));
        long orderId = created.get("id").asLong();

        ResponseEntity<JsonNode> res = pay(orderId);

        assertStatus(res, 200);
        JsonNode body = res.getBody();
        assertThat(body.get("status").asText()).isEqualTo("PAID");
        assertThat(body.get("paidAt").isNull()).isFalse();
        assertThat(time(body, "paidAt")).isAfterOrEqualTo(time(created, "createdAt"));
        assertThat(body.get("id").asLong()).isEqualTo(orderId);
        assertThat(body.get("items")).hasSize(2);
        assertStock(a, 7, 0);
        assertStock(b, 6, 0);
        assertThat(order(orderId)).isEqualTo(body);
    }

    @Test
    @DisplayName("R5.4 결제 승인 후 쿠폰 usedCount 는 유지된다")
    void r5_4_approvedKeepsCouponUsed() {
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder_withCoupon(code);

        assertStatus(pay(orderId), 200);

        assertUsedCount(code, 1);
    }

    private long newOrder_withCoupon(String code) {
        return newOrder(uniqueUser(), code, items(newProduct(1000, 10), 1)).get("id").asLong();
    }

    // ------------------------------------------------------------------ R5.3 PG 요청 검증

    @Test
    @DisplayName("R5.3/PG POST /v1/payments 로 Idempotency-Key=클라이언트 키, 본문 {orderId, amount, cardToken(숫자 타입)} 를 보낸다")
    void r5_3_pgPaymentRequestContract() throws Exception {
        long productId = newProduct(1500, 10);
        long orderId = newOrder(productId, 2).get("id").asLong();
        String key = uniqueKey();

        assertStatus(pay(orderId, key, "tok_visa_1234"), 200);

        assertThat(PG.requests()).hasSize(1);
        Recorded req = PG.requests().get(0);
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/v1/payments");
        assertThat(req.header("Idempotency-Key")).isEqualTo(key);
        assertThat(req.header("Content-Type")).startsWith("application/json");
        JsonNode body = pgBody(req);
        assertThat(body.get("orderId").isIntegralNumber()).isTrue();
        assertThat(body.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(body.get("amount").isIntegralNumber()).isTrue();
        assertThat(body.get("amount").asLong()).isEqualTo(3000);
        assertThat(body.get("cardToken").asText()).isEqualTo("tok_visa_1234");
    }

    @Test
    @DisplayName("R5.3 cardToken 은 가공 없이(앞뒤 공백·특수문자 포함) 그대로 전달된다")
    void r5_3_cardTokenForwardedVerbatim() throws Exception {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        String token = "  tok/+=\"quote\" é한글\t ";

        assertStatus(pay(orderId, uniqueKey(), token), 200);

        assertThat(pgBody(PG.requests().get(0)).get("cardToken").asText()).isEqualTo(token);
    }

    @Test
    @DisplayName("R5.3 PG amount 는 할인 후 totalPrice 이다")
    void r5_3_amountIsTotalPriceAfterDiscount() throws Exception {
        String code = newCoupon("RATE", 10, 5);
        long productId = newProduct(10_000, 10);
        long orderId = newOrder(uniqueUser(), code, items(productId, 2)).get("id").asLong();

        assertStatus(pay(orderId), 200);

        assertThat(pgBody(PG.requests().get(0)).get("amount").asLong()).isEqualTo(18_000);
    }

    @Test
    @DisplayName("C1 int 를 넘는 totalPrice(100억)도 PG amount 로 정확히 전달된다")
    void c1_amountBeyondIntRangeForwardedToGateway() throws Exception {
        long productId = newProduct(10_000_000L, 1000);
        long orderId = newOrder(productId, 1000).get("id").asLong();

        ResponseEntity<JsonNode> res = pay(orderId);

        assertStatus(res, 200);
        assertThat(res.getBody().get("totalPrice").asLong()).isEqualTo(10_000_000_000L);
        JsonNode amount = pgBody(PG.requests().get(0)).get("amount");
        assertThat(amount.isIntegralNumber()).isTrue();
        assertThat(amount.asLong()).isEqualTo(10_000_000_000L);
    }

    @Test
    @DisplayName("R5.3 PG 가 느려도(1초) 2초 안이면 정상 승인된다")
    void r5_3_gatewayResponseWithinTimeoutIsAccepted() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        PG.respondWith(r -> Response.json("{\"paymentId\":\"pay-slow\",\"status\":\"APPROVED\"}").delayed(1000));

        assertStatus(pay(orderId), 200);
        assertThat(statusOf(orderId)).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------ R5.5 거절

    @Test
    @DisplayName("R5.5 거절되면 402 PAYMENT_DECLINED, 주문 PAYMENT_FAILED, 예약 복원(stock 불변), 쿠폰 복원")
    void r5_5_declinedFailsOrderAndRestoresReservations() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 3)).get("id").asLong();
        assertStock(productId, 10, 3);

        ResponseEntity<JsonNode> res = pay(orderId, uniqueKey(), "decline_card");

        assertProblem(res, 402, "PAYMENT_DECLINED");
        assertThat(statusOf(orderId)).isEqualTo("PAYMENT_FAILED");
        assertThat(order(orderId).get("paidAt").isNull()).isTrue();
        assertStock(productId, 10, 0);
        assertUsedCount(code, 0);
    }

    @Test
    @DisplayName("R5.5 거절 뒤 복원된 재고는 다른 주문이 다시 쓸 수 있다")
    void r5_5_restoredStockIsOrderableAgain() {
        long productId = newProduct(1000, 3);
        long orderId = newOrder(productId, 3).get("id").asLong();
        assertProblem(placeOrder(uniqueUser(), null, productId, 1), 409, "INSUFFICIENT_STOCK");

        assertProblem(pay(orderId, uniqueKey(), "decline_card"), 402, "PAYMENT_DECLINED");

        assertStatus(placeOrder(uniqueUser(), null, productId, 3), 201);
    }

    @Test
    @DisplayName("R5.5 거절된 주문은 다시 결제할 수 없다 (409 INVALID_STATE)")
    void r5_5_declinedOrderCannotBePaidAgain() {
        long orderId = newOrder(newProduct(1000, 3), 1).get("id").asLong();
        pay(orderId, uniqueKey(), "decline_card");

        assertProblem(pay(orderId), 409, "INVALID_STATE");
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
    }

    // ------------------------------------------------------------------ R5.6 PG 장애

    @ParameterizedTest(name = "R5.6 PG 가 {0} 응답이면 503 PAYMENT_GATEWAY_UNAVAILABLE, 주문·재고·쿠폰 불변")
    @ValueSource(ints = {500, 502, 503, 504})
    void r5_6_gateway5xxLeavesEverythingUnchanged(int pgStatus) {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 3)).get("id").asLong();
        PG.respondWith(r -> Response.status(pgStatus));

        ResponseEntity<JsonNode> res = pay(orderId);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertUntouched(orderId, productId, 10, 3);
        assertUsedCount(code, 1);
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
    }

    @Test
    @DisplayName("R5.6 PG 가 응답 없이 연결을 끊으면 503, 주문·재고·쿠폰 불변")
    void r5_6_gatewayConnectionDroppedLeavesEverythingUnchanged() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 3)).get("id").asLong();
        PG.respondWith(r -> Response.drop());

        ResponseEntity<JsonNode> res = pay(orderId);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertUntouched(orderId, productId, 10, 3);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R5.6 PG 가 2초 안에 응답하지 않으면 503 이고 응답은 대략 2초대에 돌아온다, 주문·재고·쿠폰 불변")
    void r5_6_gatewayTimeoutAfterTwoSeconds() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 3)).get("id").asLong();
        PG.respondWith(r -> Response.json("{\"paymentId\":\"pay-late\",\"status\":\"APPROVED\"}").delayed(6000));

        long startedAt = System.nanoTime();
        ResponseEntity<JsonNode> res = pay(orderId);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isBetween(Duration.ofMillis(1900), Duration.ofMillis(3500));
        assertUntouched(orderId, productId, 10, 3);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R5.6 PG 가 늦게 승인해도(타임아웃 후) 주문은 PAID 로 바뀌지 않는다")
    void r5_6_lateApprovalDoesNotChangeOrder() throws Exception {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 1).get("id").asLong();
        PG.respondWith(r -> Response.json("{\"paymentId\":\"pay-late\",\"status\":\"APPROVED\"}").delayed(3000));
        assertProblem(pay(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        // PG 가 뒤늦게 응답을 보낼 시간을 충분히 준 뒤에도 상태가 같아야 한다.
        long deadline = System.nanoTime() + Duration.ofMillis(1500).toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(statusOf(orderId)).isEqualTo("PENDING_PAYMENT");
            Thread.sleep(100);
        }
        assertStock(productId, 10, 1);
    }

    @Test
    @DisplayName("R5.6 PG 장애 후 같은 주문은 다시 결제할 수 있다 (진행 표식이 남지 않는다)")
    void r5_6_orderIsPayableAfterGatewayFailure() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 1).get("id").asLong();
        PG.respondWith(r -> Response.status(500));
        assertProblem(pay(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        PG.reset();

        assertStatus(pay(orderId), 200);

        assertStock(productId, 9, 0);
    }

    // ------------------------------------------------------------------ R5.2 상태·404

    @Test
    @DisplayName("R5.2 이미 PAID 인 주문을 다른 키로 다시 결제하면 409 INVALID_STATE, PG 재호출 없음")
    void r5_2_alreadyPaidReturns409() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 1).get("id").asLong();
        payOk(orderId);

        assertProblem(pay(orderId), 409, "INVALID_STATE");

        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
        assertStock(productId, 9, 0);
    }

    @Test
    @DisplayName("R5.2 CANCELLED 주문 결제는 409 INVALID_STATE, PG 호출 없음")
    void r5_2_cancelledOrderReturns409() {
        long orderId = newOrder(newProduct(1000, 10), 1).get("id").asLong();
        assertStatus(cancel(orderId), 200);

        assertProblem(pay(orderId), 409, "INVALID_STATE");

        assertThat(PG.requests()).isEmpty();
    }

    @Test
    @DisplayName("R5.2 SHIPPED 주문 결제는 409 INVALID_STATE")
    void r5_2_shippedOrderReturns409() {
        long orderId = newOrder(newProduct(1000, 10), 1).get("id").asLong();
        payOk(orderId);
        assertStatus(ship(orderId), 200);

        assertProblem(pay(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R5.2 REFUNDED 주문 결제는 409 INVALID_STATE")
    void r5_2_refundedOrderReturns409() {
        long orderId = newOrder(newProduct(1000, 10), 1).get("id").asLong();
        payOk(orderId);
        assertStatus(cancel(orderId), 200);

        assertProblem(pay(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R5.2 expiresAt 이 지난 주문(DB 에서 만료 시각을 과거로 당김) 결제는 409 INVALID_STATE, PG 호출 없음, 예약 복원")
    void r5_2_orderPastExpiresAtReturns409() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 2).get("id").asLong();
        jdbc.update("update orders set created_at = created_at - interval '1 hour', "
                + "expires_at = now() - interval '1 minute' where id = ?", orderId);

        ResponseEntity<JsonNode> res = pay(orderId);

        assertProblem(res, 409, "INVALID_STATE");
        assertThat(PG.requests()).isEmpty();
        assertThat(statusOf(orderId)).isEqualTo("EXPIRED");
        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R5.2 없는 주문 결제는 404 ORDER_NOT_FOUND, PG 호출 없음")
    void r5_2_unknownOrderReturns404() {
        assertProblem(pay(999_999_999L), 404, "ORDER_NOT_FOUND");

        assertThat(PG.requests()).isEmpty();
    }

    // ------------------------------------------------------------------ R5.1 입력 검증

    @Test
    @DisplayName("R5.1 cardToken 누락·null·빈 문자열·공백은 400, PG 호출 없음, 주문 불변")
    void r5_1_invalidCardTokenRejected() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 1).get("id").asLong();
        String path = "/api/orders/" + orderId + "/pay";

        assertProblem(post(path, map(), "Idempotency-Key", uniqueKey()), 400, "VALIDATION_ERROR");
        assertProblem(post(path, map("cardToken", null), "Idempotency-Key", uniqueKey()), 400, "VALIDATION_ERROR");
        assertProblem(post(path, map("cardToken", ""), "Idempotency-Key", uniqueKey()), 400, "VALIDATION_ERROR");
        assertProblem(post(path, map("cardToken", "   "), "Idempotency-Key", uniqueKey()), 400, "VALIDATION_ERROR");
        assertProblem(post(path, map("cardToken", List.of("tok")), "Idempotency-Key", uniqueKey()), 400,
                "VALIDATION_ERROR");

        assertThat(PG.requests()).isEmpty();
        assertUntouched(orderId, productId, 10, 1);
    }

    @Test
    @DisplayName("R5.1 cardToken 이 JSON 숫자·불리언이면 String 으로 변환하지 않고 400 problem+json, PG 호출 없음, 주문 불변")
    void r5_1_nonStringCardTokenRejected() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 1).get("id").asLong();
        String path = "/api/orders/" + orderId + "/pay";

        for (String raw : new String[] {"{\"cardToken\":12345}", "{\"cardToken\":1.5}", "{\"cardToken\":true}"}) {
            ResponseEntity<JsonNode> res = post(path, raw, "Idempotency-Key", uniqueKey());
            assertProblem(res, 400, "VALIDATION_ERROR");
            assertThat(res.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        }

        assertThat(PG.requests()).isEmpty();
        assertUntouched(orderId, productId, 10, 1);
    }

    @Test
    @DisplayName("R5.1 본문이 깨진 JSON 이거나 비어 있으면 400")
    void r5_1_malformedBodyRejected() {
        long orderId = newOrder(newProduct(1000, 10), 1).get("id").asLong();
        String path = "/api/orders/" + orderId + "/pay";

        assertProblem(post(path, "{\"cardToken\":", "Idempotency-Key", uniqueKey()), 400, "VALIDATION_ERROR");
        assertProblem(post(path, "", "Idempotency-Key", uniqueKey()), 400, "VALIDATION_ERROR");
        assertProblem(post(path, null, "Idempotency-Key", uniqueKey()), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R5.1 orderId 가 숫자가 아니면 400")
    void r5_1_nonNumericOrderIdRejected() {
        ResponseEntity<JsonNode> res = post("/api/orders/abc/pay", Map.of("cardToken", "t"), "Idempotency-Key",
                uniqueKey());

        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------ R5.7 0원 주문

    @Test
    @DisplayName("R5.7 totalPrice 가 0(쿠폰 전액 할인)이면 PG 를 호출하지 않고 PAID, stock·reserved 감소")
    void r5_7_zeroTotalSkipsGatewayAndMarksPaid() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 1_000_000, 5);
        JsonNode created = newOrder(uniqueUser(), code, items(productId, 2));
        assertThat(created.get("totalPrice").asLong()).isZero();

        ResponseEntity<JsonNode> res = pay(created.get("id").asLong());

        assertStatus(res, 200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(res.getBody().get("paidAt").isNull()).isFalse();
        assertThat(PG.requests()).isEmpty();
        assertStock(productId, 8, 0);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R5.7 0원 주문은 PG 가 죽어 있어도 결제된다")
    void r5_7_zeroTotalIgnoresGatewayOutage() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("RATE", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 1)).get("id").asLong();
        PG.respondWith(r -> Response.status(500));

        assertStatus(pay(orderId), 200);

        assertThat(PG.requests()).isEmpty();
    }
}
