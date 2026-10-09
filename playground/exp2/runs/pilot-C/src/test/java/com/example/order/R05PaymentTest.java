package com.example.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R5 결제")
class R05PaymentTest extends AbstractIntegrationTest {

    private void assertPending(ApiResponse order, long productId, int reserved, int stock) {
        ApiResponse o = getOrder(order.id());
        assertThat(o.json("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.json("paidAt").isNull()).isTrue();
        assertThat(reservedOf(productId)).isEqualTo(reserved);
        assertThat(stockOf(productId)).isEqualTo(stock);
    }

    // ------------------------------------------------------------- R5.3 / R5.4 approve

    @Test
    @DisplayName("R5.4 승인 -> 200 PAID, paidAt 기록, R3.5 형태, stock/reserved 감소")
    void approve_marksPaidAndDecrementsStock() {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(500, 7);
        ApiResponse order = newOrder(uniqueUser(), null, p1, 3, p2, 2);
        stubPgPayment("APPROVED", "pay-ok");
        Instant before = Instant.now();

        ApiResponse r = pay(order.id(), uniqueKey(), "tok_visa");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(r.json("status").asText()).isEqualTo("PAID");
        assertThat(instant(r.json("paidAt"))).isBetween(before.minusSeconds(2), Instant.now().plusSeconds(2));
        assertThat(stockOf(p1)).isEqualTo(7);
        assertThat(reservedOf(p1)).isZero();
        assertThat(stockOf(p2)).isEqualTo(5);
        assertThat(reservedOf(p2)).isZero();
        ApiResponse fetched = getOrder(order.id());
        assertThat(fetched.json("status").asText()).isEqualTo("PAID");
        assertThat(instant(fetched.json("paidAt"))).isEqualTo(instant(r.json("paidAt")));
    }

    @Test
    @DisplayName("R5.3 PG 요청: Idempotency-Key=클라이언트 키, 본문 orderId/amount(totalPrice)/cardToken")
    void approve_pgRequestCarriesKeyAndBody() {
        long p = newProduct(10_000, 10);
        String coupon = newCoupon("FIXED", 2_500, null, null, 5);
        ApiResponse order = newOrder(uniqueUser(), coupon, p, 2); // subtotal 20000, total 17500
        stubPgPayment("APPROVED", "pay-body");
        String key = uniqueKey();

        pay(order.id(), key, "tok_special-123");

        WIREMOCK.verify(exactly(1), postRequestedFor(urlEqualTo("/v1/payments"))
                .withHeader("Idempotency-Key", equalTo(key))
                .withRequestBody(equalToJson("{\"orderId\":" + order.id()
                        + ",\"amount\":17500,\"cardToken\":\"tok_special-123\"}")));
    }

    @Test
    @DisplayName("R5.3 / C1 int 범위를 넘는 금액이 PG amount 에 그대로 전달된다")
    void approve_bigAmountPassedToPg() {
        long p = newProduct(10_000_000, 1_000);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1_000);
        stubPgPayment("APPROVED", "pay-big");

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json("totalPrice").asLong()).isEqualTo(10_000_000_000L);
        WIREMOCK.verify(exactly(1), postRequestedFor(urlEqualTo("/v1/payments"))
                .withRequestBody(equalToJson("{\"orderId\":" + order.id()
                        + ",\"amount\":10000000000,\"cardToken\":\"tok\"}")));
    }

    @Test
    @DisplayName("R5.6 경계: PG 가 1초 지연 후 승인하면(2초 이내) 정상 200")
    void approve_slowButWithinTimeout_200() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPaymentDelayed("APPROVED", "pay-1s", 1_000);

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json("status").asText()).isEqualTo("PAID");
    }

    // ------------------------------------------------------------- R5.5 decline

    @Test
    @DisplayName("R5.5 거절 -> 402 PAYMENT_DECLINED(problem+json), 주문 PAYMENT_FAILED, 예약·쿠폰 복원, stock 불변")
    void decline_402_failedAndRestored() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse order = newOrder(uniqueUser(), coupon, p, 4);
        assertThat(reservedOf(p)).isEqualTo(4);
        assertThat(usedCountOf(coupon)).isEqualTo(1);
        stubPgPayment("DECLINED", "pay-no");

        ApiResponse r = pay(order.id(), uniqueKey(), "tok_bad");

        assertThat(r.status()).isEqualTo(402);
        assertThat(r.code()).isEqualTo("PAYMENT_DECLINED");
        assertThat(r.isProblemJson()).isTrue();
        ApiResponse o = getOrder(order.id());
        assertThat(o.json("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(o.json("paidAt").isNull()).isTrue();
        assertThat(reservedOf(p)).isZero();
        assertThat(stockOf(p)).isEqualTo(10);
        assertThat(usedCountOf(coupon)).isZero();
    }

    @Test
    @DisplayName("R5.5 PAYMENT_FAILED 주문은 다시 결제할 수 없다 -> 409 INVALID_STATE")
    void decline_thenPayAgain_409() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("DECLINED", "pay-no2");
        pay(order.id(), uniqueKey(), "tok");
        stubPgPayment("APPROVED", "pay-yes");

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(reservedOf(p)).isZero();
        assertThat(stockOf(p)).isEqualTo(10);
    }

    // ------------------------------------------------------------- R5.6 PG failure

    @Test
    @DisplayName("R5.6 PG 500 -> 503 PAYMENT_GATEWAY_UNAVAILABLE, 주문·재고·쿠폰 불변")
    void pg500_503_unchanged() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse order = newOrder(uniqueUser(), coupon, p, 2);
        stubPgPaymentStatus(500);

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(r.isProblemJson()).isTrue();
        assertPending(order, p, 2, 10);
        assertThat(usedCountOf(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.6 PG 503 -> 503, 불변")
    void pg503_503_unchanged() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 2);
        stubPgPaymentStatus(503);

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertPending(order, p, 2, 10);
    }

    @Test
    @DisplayName("R5.6 PG 연결 끊김(CONNECTION_RESET) -> 503, 불변")
    void pgConnectionReset_503_unchanged() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse order = newOrder(uniqueUser(), coupon, p, 2);
        stubPg(WireMock.post(urlEqualTo("/v1/payments")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertPending(order, p, 2, 10);
        assertThat(usedCountOf(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.6 PG 빈 응답(EMPTY_RESPONSE) -> 503, 불변")
    void pgEmptyResponse_503_unchanged() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 2);
        stubPg(WireMock.post(urlEqualTo("/v1/payments")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(503);
        assertPending(order, p, 2, 10);
    }

    @Test
    @DisplayName("R5.6 PG 가 2초 안에 응답하지 않으면(3초 지연) 503, 대기 없이 끊고 불변")
    void pgTimeout_503_unchanged() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse order = newOrder(uniqueUser(), coupon, p, 2);
        stubPgPaymentDelayed("APPROVED", "pay-late", 6_000);
        long start = System.nanoTime();

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isLessThan(Duration.ofMillis(5_000));
        assertPending(order, p, 2, 10);
        assertThat(usedCountOf(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.6 PG 장애 뒤 PG 가 복구되면 새 키로도 결제 가능")
    void afterPgFailure_canPayAgain() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 2);
        stubPgPaymentStatus(500);
        assertThat(pay(order.id(), uniqueKey(), "tok").status()).isEqualTo(503);
        WIREMOCK.resetAll();
        stubPgPayment("APPROVED", "pay-recovered");

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(200);
        assertThat(stockOf(p)).isEqualTo(8);
    }

    // ------------------------------------------------------------- R5.7 zero amount

    @Test
    @DisplayName("R5.7 totalPrice 0 -> PG 호출 없이 200 PAID, stock/reserved 감소")
    void zeroTotal_skipsPg() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 50_000, null, null, 3);
        ApiResponse order = newOrder(uniqueUser(), coupon, p, 2);
        assertThat(order.json("totalPrice").asLong()).isZero();

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json("status").asText()).isEqualTo("PAID");
        assertThat(r.json("paidAt").isNull()).isFalse();
        WIREMOCK.verify(exactly(0), postRequestedFor(urlEqualTo("/v1/payments")));
        assertThat(stockOf(p)).isEqualTo(8);
        assertThat(reservedOf(p)).isZero();
        assertThat(usedCountOf(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.7 totalPrice 0 이면 PG 가 장애여도 결제 성공")
    void zeroTotal_pgDownStillPaid() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("RATE", 100, null, null, 3);
        ApiResponse order = newOrder(uniqueUser(), coupon, p, 1);
        stubPgPaymentStatus(500);

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(200);
        WIREMOCK.verify(exactly(0), postRequestedFor(urlEqualTo("/v1/payments")));
    }

    // ------------------------------------------------------------- R5.2 state / 404

    @Test
    @DisplayName("R5.2 이미 PAID 인 주문 재결제(새 키) -> 409 INVALID_STATE, PG 추가 호출 없음")
    void pay_alreadyPaid_409() {
        long p = newProduct(1_000, 10);
        ApiResponse paid = newPaidOrder(uniqueUser(), null, p, 1);

        ApiResponse r = pay(paid.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(pgPaymentRequestCount()).isEqualTo(1);
        assertThat(stockOf(p)).isEqualTo(9);
    }

    @Test
    @DisplayName("R5.2 CANCELLED 주문 결제 -> 409 INVALID_STATE, PG 미호출")
    void pay_cancelled_409() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        cancel(order.id());
        stubPgPayment("APPROVED", "pay-c");

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(pgPaymentRequestCount()).isZero();
        assertThat(statusOf(order.id())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("R5.2 SHIPPED 주문 결제 -> 409")
    void pay_shipped_409() {
        long p = newProduct(1_000, 10);
        ApiResponse paid = newPaidOrder(uniqueUser(), null, p, 1);
        ship(paid.id());

        assertThat(pay(paid.id(), uniqueKey(), "tok").status()).isEqualTo(409);
    }

    @Test
    @DisplayName("R5.2 없는 주문 결제 -> 404 ORDER_NOT_FOUND, PG 미호출")
    void pay_unknownOrder_404() {
        stubPgPayment("APPROVED", "pay-x");

        ApiResponse r = pay(Long.MAX_VALUE - 1, uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(pgPaymentRequestCount()).isZero();
    }

    @Test
    @DisplayName("R5.1 cardToken 누락/공백/null -> 400, PG 미호출")
    void pay_invalidCardToken_400() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("APPROVED", "pay-x");
        String path = "/api/orders/" + order.id() + "/pay";

        assertThat(post(path, "{}", headers("Idempotency-Key", uniqueKey())).status()).isEqualTo(400);
        assertThat(post(path, "{\"cardToken\":null}", headers("Idempotency-Key", uniqueKey())).status()).isEqualTo(400);
        assertThat(post(path, "{\"cardToken\":\"   \"}", headers("Idempotency-Key", uniqueKey())).status()).isEqualTo(400);
        assertThat(post(path, "{\"cardToken\":\"\"}", headers("Idempotency-Key", uniqueKey())).status()).isEqualTo(400);
        assertThat(pgPaymentRequestCount()).isZero();
        assertThat(statusOf(order.id())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R5.1 Idempotency-Key 공백/65자 -> 400")
    void pay_invalidKey_400() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);

        assertThat(pay(order.id(), "   ", "tok").status()).isEqualTo(400);
        assertThat(pay(order.id(), "k".repeat(65), "tok").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R5.1 숫자가 아닌 주문 id -> 400")
    void pay_nonNumericId_400() {
        ApiResponse r = post("/api/orders/abc/pay", obj().put("cardToken", "tok"),
                headers("Idempotency-Key", uniqueKey()));

        assertThat(r.status()).isEqualTo(400);
    }
}
