package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R5. 결제")
class R5PaymentTest extends IntegrationTestBase {

    private long newOrder(long productId, int quantity) {
        return orderIdOk(uid("u"), orderBody(null, item(productId, quantity)));
    }

    // ---------------------------------------------------------------- R5.1 / R5.4 (승인)

    @Test
    @DisplayName("R5.1/R5.4 승인되면 200, 본문은 R3.5 형태이고 status=PAID, paidAt 이 기록된다")
    void r5_4_approved_returns200WithPaidOrder() {
        long productId = product(10_000, 5);
        long orderId = newOrder(productId, 2);
        Instant before = Instant.now();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok_ok");

        assertThat(statusOf(r)).isEqualTo(200);
        JsonNode o = json(r);
        assertThat(o.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(o.get("id").asLong()).isEqualTo(orderId);
        assertThat(o.get("status").asText()).isEqualTo("PAID");
        assertThat(Instant.parse(o.get("paidAt").asText())).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("R5.4 승인되면 각 상품의 stock 과 reserved 가 주문 수량만큼 줄어든다")
    void r5_4_approved_decreasesStockAndReserved() {
        long p1 = product(1_000, 10);
        long p2 = product(2_000, 10);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(p1, 3), item(p2, 4)));

        pay(orderId, uid("pk"), "tok_ok");

        assertThat(stockOf(p1)).isEqualTo(7);
        assertThat(reservedOf(p1)).isZero();
        assertThat(stockOf(p2)).isEqualTo(6);
        assertThat(reservedOf(p2)).isZero();
    }

    @Test
    @DisplayName("R5.4 승인 후 주문 조회에도 PAID 와 paidAt 이 반영된다")
    void r5_4_approved_isVisibleInOrderGet() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);

        pay(orderId, uid("pk"), "tok_ok");

        JsonNode o = json(getOrder(orderId));
        assertThat(o.get("status").asText()).isEqualTo("PAID");
        assertThat(o.get("paidAt").isNull()).isFalse();
    }

    // ---------------------------------------------------------------- R5.3 (PG 요청 계약)

    @Test
    @DisplayName("R5.3 PG 에 {orderId, amount, cardToken} 을 한 번 요청한다")
    void r5_3_pgRequestBody_hasOrderIdAmountAndCardToken() throws Exception {
        long productId = product(10_000, 5);
        long orderId = newOrder(productId, 3);

        pay(orderId, uid("pk"), "tok_visa_4242");

        assertThat(PG.paymentCalls()).isEqualTo(1);
        JsonNode sent = objectMapper.readTree(PG.receivedPayments().get(0).body());
        assertThat(sent.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(sent.get("amount").asLong()).isEqualTo(30_000L);
        assertThat(sent.get("cardToken").asText()).isEqualTo("tok_visa_4242");
    }

    @Test
    @DisplayName("R5.3 PG 요청의 Idempotency-Key 는 클라이언트가 보낸 값 그대로다")
    void r5_3_pgIdempotencyKey_isClientKeyVerbatim() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        String key = "client-key-" + uid("v");

        pay(orderId, key, "tok");

        assertThat(PG.receivedPayments()).hasSize(1);
        assertThat(PG.receivedPayments().get(0).idempotencyKey()).isEqualTo(key);
    }

    @Test
    @DisplayName("R5.3 cardToken 은 가공 없이 그대로 전달된다 (특수문자·공백 포함)")
    void r5_3_cardToken_isForwardedAsIs() throws Exception {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        String token = " tok/+=&\"한글\\ ";

        pay(orderId, uid("pk"), token);

        JsonNode sent = objectMapper.readTree(PG.receivedPayments().get(0).body());
        assertThat(sent.get("cardToken").asText()).isEqualTo(token);
    }

    @Test
    @DisplayName("R5.3 쿠폰 할인이 적용된 주문은 할인 후 totalPrice 가 PG amount 로 전달된다")
    void r5_3_pgAmount_isTotalPriceAfterDiscount() throws Exception {
        long productId = product(10_000, 5);
        String code = uniqueCode("AMT");
        createCoupon(code, "FIXED", 3_000, 0, null, 3);
        long orderId = orderIdOk(uid("u"), orderBody(code, item(productId, 2)));

        pay(orderId, uid("pk"), "tok");

        JsonNode sent = objectMapper.readTree(PG.receivedPayments().get(0).body());
        assertThat(sent.get("amount").asLong()).isEqualTo(17_000L);
    }

    // ---------------------------------------------------------------- R5.2 (상태/404)

    @Test
    @DisplayName("R5.2 이미 결제된 주문을 다른 키로 다시 결제하면 409 INVALID_STATE 이고 PG 는 재호출되지 않는다")
    void r5_2_alreadyPaid_returns409() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        pay(orderId, uid("pk"), "tok");

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.2 취소된 주문은 결제할 수 없다 (409 INVALID_STATE, PG 미호출)")
    void r5_2_cancelledOrder_returns409() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        cancel(orderId);

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).isZero();
    }

    @Test
    @DisplayName("R5.2 결제 거절로 PAYMENT_FAILED 가 된 주문은 다시 결제할 수 없다")
    void r5_2_paymentFailedOrder_returns409() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        PG.declinePayments();
        pay(orderId, uid("pk"), "tok");
        PG.approvePayments();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.2 없는 주문은 404 ORDER_NOT_FOUND 이고 PG 는 호출되지 않는다")
    void r5_2_unknownOrder_returns404() {
        ResponseEntity<String> r = pay(987_654_321L, uid("pk"), "tok");

        assertProblem(r, 404, "ORDER_NOT_FOUND");
        assertThat(PG.paymentCalls()).isZero();
    }

    @Test
    @DisplayName("R5.1 cardToken 이 공백뿐이면 400 이고 PG 는 호출되지 않는다")
    void r5_1_blankCardToken_returns400() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);

        ResponseEntity<String> r = pay(orderId, uid("pk"), "   ");

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(PG.paymentCalls()).isZero();
        assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R5.1 cardToken 이 빈 문자열이면 400")
    void r5_1_emptyCardToken_returns400() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);

        assertProblem(pay(orderId, uid("pk"), ""), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R5.1 cardToken 이 없으면 400")
    void r5_1_missingCardToken_returns400() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);

        ResponseEntity<String> r = postWithKey("/api/orders/" + orderId + "/pay", Map.of(), uid("pk"));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R5.1 결제 본문이 비어 있으면 400")
    void r5_1_emptyBody_returns400() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);

        ResponseEntity<String> r = postWithKey("/api/orders/" + orderId + "/pay", null, uid("pk"));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R5.1 결제 경로의 주문 id 가 숫자가 아니면 400")
    void r5_1_nonNumericOrderId_returns400() {
        ResponseEntity<String> r = postWithKey("/api/orders/abc/pay", Map.of("cardToken", "t"), uid("pk"));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    // ---------------------------------------------------------------- R5.5 (거절)

    @Test
    @DisplayName("R5.5 PG 가 거절하면 402 PAYMENT_DECLINED")
    void r5_5_declined_returns402() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        PG.declinePayments();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok_declined");

        assertProblem(r, 402, "PAYMENT_DECLINED");
        assertThat(PG.paymentCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.5 거절되면 주문은 PAYMENT_FAILED 가 되고 paidAt 은 없다")
    void r5_5_declined_marksOrderPaymentFailed() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        PG.declinePayments();

        pay(orderId, uid("pk"), "tok");

        JsonNode o = json(getOrder(orderId));
        assertThat(o.get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(o.get("paidAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("R5.5 거절되면 예약이 복원되고 stock 은 그대로다")
    void r5_5_declined_restoresReservation() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 3);
        PG.declinePayments();

        pay(orderId, uid("pk"), "tok");

        assertThat(reservedOf(productId)).isZero();
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(availableOf(productId)).isEqualTo(5);
    }

    @Test
    @DisplayName("R5.5 거절되면 쿠폰 사용이 복원된다")
    void r5_5_declined_restoresCouponUsage() {
        long productId = product(10_000, 5);
        String code = uniqueCode("DECL");
        createCoupon(code, "FIXED", 1_000, 0, null, 3);
        long orderId = orderIdOk(uid("u"), orderBody(code, item(productId, 1)));
        PG.declinePayments();

        pay(orderId, uid("pk"), "tok");

        assertThat(usedCountOf(code)).isZero();
    }

    // ---------------------------------------------------------------- R5.6 (PG 장애)

    @Test
    @DisplayName("R5.6 PG 가 5xx 를 주면 503 PAYMENT_GATEWAY_UNAVAILABLE")
    void r5_6_pg5xx_returns503() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        PG.failPaymentsWith5xx();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(PG.paymentCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.6 PG 5xx 이면 주문·재고·쿠폰은 바뀌지 않는다")
    void r5_6_pg5xx_leavesEverythingUnchanged() {
        long productId = product(10_000, 5);
        String code = uniqueCode("F5XX");
        createCoupon(code, "FIXED", 1_000, 0, null, 3);
        long orderId = orderIdOk(uid("u"), orderBody(code, item(productId, 2)));
        PG.failPaymentsWith5xx();

        pay(orderId, uid("pk"), "tok");

        JsonNode o = json(getOrder(orderId));
        assertThat(o.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.get("paidAt").isNull()).isTrue();
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(reservedOf(productId)).isEqualTo(2);
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R5.6 PG 연결에 실패하면 503 이고 주문은 PENDING_PAYMENT 로 남는다")
    void r5_6_pgConnectionRefused_returns503() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        PG.stop();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(reservedOf(productId)).isEqualTo(1);
        assertThat(stockOf(productId)).isEqualTo(5);
    }

    @Test
    @DisplayName("R5.6 PG 응답이 2초를 넘으면 약 2초 만에 503 을 돌려주고 상태는 불변이다")
    void r5_6_pgTimeout_returns503Within2sAndKeepsState() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        PG.delayPayments(6_000);
        long startedAt = System.nanoTime();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isLessThan(Duration.ofMillis(4_500));
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1_800));
        assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(reservedOf(productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.6 PG 가 헤더만 먼저 보내고 본문을 2초 넘게 멈추면(전체 응답 지연) 약 2초 만에 503 이고 상태는 불변이다")
    void r5_6_pgBodyStall_returns503Within2s() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        PG.stallPaymentBodies(7_000);
        long startedAt = System.nanoTime();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isLessThan(Duration.ofMillis(4_500));
        assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(reservedOf(productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.6 PG 응답이 2초 이내(1초)로 늦으면 정상 승인된다 (타임아웃 경계)")
    void r5_6_pgSlowButWithinTimeout_isApproved() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        PG.delayPayments(1_000);

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertThat(statusOf(r)).isEqualTo(200);
        assertThat(json(r).get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R5.6 PG 장애 뒤 복구되면 같은 주문을 다시 결제할 수 있다")
    void r5_6_afterRecovery_orderCanBePaid() {
        long productId = product(1_000, 5);
        long orderId = newOrder(productId, 1);
        PG.failPaymentsWith5xx();
        pay(orderId, uid("pk"), "tok");
        PG.approvePayments();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertThat(statusOf(r)).isEqualTo(200);
        assertThat(stockOf(productId)).isEqualTo(4);
    }

    // ---------------------------------------------------------------- R5.7 (0원 주문)

    @Test
    @DisplayName("R5.7 totalPrice 가 0 이면 PG 를 호출하지 않고 바로 승인한다")
    void r5_7_zeroTotal_skipsPgAndApproves() {
        long productId = product(10_000, 5);
        String code = uniqueCode("FREE");
        createCoupon(code, "RATE", 100, 0, null, 3);
        JsonNode created = orderOk(uid("u"), orderBody(code, item(productId, 1)));
        assertThat(created.get("totalPrice").asLong()).isZero();

        ResponseEntity<String> r = pay(created.get("id").asLong(), uid("pk"), "tok");

        assertThat(statusOf(r)).isEqualTo(200);
        assertThat(json(r).get("status").asText()).isEqualTo("PAID");
        assertThat(json(r).get("paidAt").isNull()).isFalse();
        assertThat(PG.paymentCalls()).isZero();
    }

    @Test
    @DisplayName("R5.7 0원 주문 승인도 stock 과 reserved 를 주문 수량만큼 줄인다")
    void r5_7_zeroTotal_decreasesStockAndReserved() {
        long productId = product(10_000, 5);
        String code = uniqueCode("FREE2");
        createCoupon(code, "FIXED", 50_000, 0, null, 3);
        long orderId = orderIdOk(uid("u"), orderBody(code, item(productId, 2)));

        pay(orderId, uid("pk"), "tok");

        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("R5.7 0원 주문은 PG 가 장애여도 결제가 성공한다")
    void r5_7_zeroTotal_succeedsEvenWhenPgIsDown() {
        long productId = product(10_000, 5);
        String code = uniqueCode("FREE3");
        createCoupon(code, "RATE", 100, 0, null, 3);
        long orderId = orderIdOk(uid("u"), orderBody(code, item(productId, 1)));
        PG.stop();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertThat(statusOf(r)).isEqualTo(200);
    }
}
