package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api.Response;
import com.example.order.support.FakePaymentGateway.PaymentCall;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R5 결제")
class PaymentApiTest extends IntegrationTest {

    @Test
    @DisplayName("R5.3/R5.4 승인되면 200 PAID, paidAt 기록, stock·reserved 감소, PG에 키·토큰·금액 전달")
    void approved() {
        long p = createProduct(1000, 10);
        long q = createProduct(500, 10);
        String code = createCoupon("FIXED", 300, 10);
        long orderId = createOrder(uniqueUser(), code, p, 2, q, 3).get("id").asLong();
        String key = uniqueKey();

        Response r = pay(orderId, key, "card-abc");

        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        assertThat(r.contentType()).startsWith("application/json");
        JsonNode body = r.body();
        assertThat(body.get("id").asLong()).isEqualTo(orderId);
        assertThat(body.get("status").asText()).isEqualTo("PAID");
        assertThat(OffsetDateTime.parse(body.get("paidAt").asText()))
                .isAfterOrEqualTo(OffsetDateTime.parse(body.get("createdAt").asText()));
        assertThat(order(orderId)).isEqualTo(body);

        assertThat(product(p).get("stock").asLong()).isEqualTo(8);
        assertThat(product(p).get("reserved").asLong()).isZero();
        assertThat(product(q).get("stock").asLong()).isEqualTo(7);
        assertThat(product(q).get("reserved").asLong()).isZero();
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);

        List<PaymentCall> calls = PG.paymentCallsFor(orderId);
        assertThat(calls).containsExactly(new PaymentCall(key, orderId, 3200, "card-abc"));
    }

    @Test
    @DisplayName("R5.5 거절되면 402 PAYMENT_DECLINED, PAYMENT_FAILED, 예약·쿠폰 사용 복원")
    void declined() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 300, 10);
        long orderId = createOrder(uniqueUser(), code, p, 4).get("id").asLong();

        assertProblem(pay(orderId, uniqueKey(), "decline-card"), 402, "PAYMENT_DECLINED");

        JsonNode order = order(orderId);
        assertThat(order.get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(order.get("paidAt").isNull()).isTrue();
        assertThat(product(p).get("stock").asLong()).isEqualTo(10);
        assertThat(product(p).get("reserved").asLong()).isZero();
        assertThat(coupon(code).get("usedCount").asLong()).isZero();
    }

    @Test
    @DisplayName("R5.6 PG 5xx면 503 PAYMENT_GATEWAY_UNAVAILABLE, 주문·재고·쿠폰 변화 없음")
    void gatewayError() {
        assertGatewayUnavailable("error-card");
    }

    @Test
    @DisplayName("R5.6 PG가 2초 안에 응답하지 않으면 503, 주문·재고·쿠폰 변화 없음")
    void gatewayTimeout() {
        long started = System.nanoTime();
        assertGatewayUnavailable("slow-card");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2900));
    }

    private void assertGatewayUnavailable(String cardToken) {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 300, 10);
        long orderId = createOrder(uniqueUser(), code, p, 4).get("id").asLong();

        assertProblem(pay(orderId, uniqueKey(), cardToken), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(order(orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(product(p).get("stock").asLong()).isEqualTo(10);
        assertThat(product(p).get("reserved").asLong()).isEqualTo(4);
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);

        assertThat(pay(orderId, uniqueKey(), "tok").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R5.7 totalPrice가 0이면 PG를 호출하지 않고 PAID")
    void zeroTotal() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 5000, 10);
        long orderId = createOrder(uniqueUser(), code, p, 2).get("id").asLong();

        Response r = pay(orderId, uniqueKey(), "decline-would-fail");

        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        assertThat(r.body().get("status").asText()).isEqualTo("PAID");
        assertThat(r.body().get("totalPrice").asLong()).isZero();
        assertThat(PG.paymentCallsFor(orderId)).isEmpty();
        assertThat(product(p).get("stock").asLong()).isEqualTo(8);
    }

    @Test
    @DisplayName("R5.2 PENDING_PAYMENT가 아니면 409 INVALID_STATE, 없으면 404")
    void invalidState() {
        long p = createProduct(1000, 10);
        long paid = paidOrder(uniqueUser(), null, "tok", p, 1).get("id").asLong();
        assertProblem(pay(paid, uniqueKey(), "tok"), 409, "INVALID_STATE");

        long cancelled = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        api.post("/api/orders/" + cancelled + "/cancel", null);
        assertProblem(pay(cancelled, uniqueKey(), "tok"), 409, "INVALID_STATE");
        assertThat(PG.paymentCallsFor(cancelled)).isEmpty();

        assertProblem(pay(987654321L, uniqueKey(), "tok"), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R5.1 cardToken이 비었거나 공백이면 400")
    void invalidCardToken() {
        long p = createProduct(1000, 10);
        long orderId = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        assertProblem(pay(orderId, uniqueKey(), "  "), 400, "VALIDATION_ERROR");
        assertProblem(api.post("/api/orders/" + orderId + "/pay", "{}", "Idempotency-Key", uniqueKey()), 400,
                "VALIDATION_ERROR");
        assertThat(order(orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }
}
