package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.FakePaymentGateway.PaymentCall;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R5. 결제")
class PaymentApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("R5.1/R5.4 승인되면 200, PAID, paidAt 기록, stock과 reserved가 수량만큼 줄어든다")
    void approved() {
        long p = createProduct(5_000, 10);
        long q = createProduct(1_000, 10);
        String code = createCoupon();
        JsonNode created = placeOrder(newUser(), code, item(p, 2), item(q, 3));
        long orderId = created.get("id").asLong();

        Instant before = Instant.now();
        ResponseEntity<JsonNode> response = pay(orderId, newKey(), "card-ok");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("id").asLong()).isEqualTo(orderId);
        assertThat(body.get("status").asText()).isEqualTo("PAID");
        assertThat(instant(body, "paidAt")).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(body.get("totalPrice").asLong()).isEqualTo(created.get("totalPrice").asLong());
        assertThat(order(orderId)).isEqualTo(body);

        assertThat(product(p).get("stock").asInt()).isEqualTo(8);
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(product(q).get("stock").asInt()).isEqualTo(7);
        assertThat(product(q).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1); // 결제된 주문은 계속 사용 중
    }

    @Test
    @DisplayName("R5.3 PG에 {orderId, amount, cardToken}을 보내고 Idempotency-Key는 클라이언트 키를 그대로 쓴다")
    void gatewayRequest() {
        long p = createProduct(7_000, 10);
        String code = createCoupon("type", "FIXED", "value", 500);
        long orderId = placeOrder(newUser(), code, item(p, 2)).get("id").asLong();
        String key = newKey();

        assertThat(pay(orderId, key, "card-ok-1234").getStatusCode().value()).isEqualTo(200);

        List<PaymentCall> calls = PG.paymentCallsFor(orderId);
        assertThat(calls).containsExactly(new PaymentCall(key, orderId, 13_500, "card-ok-1234"));
    }

    @Test
    @DisplayName("R5.5 거절되면 402 PAYMENT_DECLINED, 주문은 PAYMENT_FAILED, 예약·쿠폰 사용 복원")
    void declined() {
        long p = createProduct(5_000, 10);
        String code = createCoupon();
        long orderId = placeOrder(newUser(), code, item(p, 4)).get("id").asLong();

        assertProblem(pay(orderId, newKey(), "decline-card"), 402, "PAYMENT_DECLINED");

        assertThat(order(orderId).get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(order(orderId).get("paidAt").isNull()).isTrue();
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("R5.6 PG 5xx면 503, 주문·재고·쿠폰은 바뀌지 않는다")
    void gatewayError() {
        long p = createProduct(5_000, 10);
        String code = createCoupon();
        long orderId = placeOrder(newUser(), code, item(p, 4)).get("id").asLong();

        assertProblem(pay(orderId, newKey(), "error-card"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertUnchanged(orderId, p, code);
        // 이후 정상 결제 가능
        assertThat(pay(orderId, newKey(), "card-ok").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("R5.6 PG가 2초 안에 응답하지 않으면 503, 주문·재고·쿠폰은 바뀌지 않는다")
    void gatewayTimeout() {
        long p = createProduct(5_000, 10);
        String code = createCoupon();
        long orderId = placeOrder(newUser(), code, item(p, 4)).get("id").asLong();

        Instant start = Instant.now();
        ResponseEntity<JsonNode> response = pay(orderId, newKey(), "timeout-card");
        Duration elapsed = Duration.between(start, Instant.now());

        assertProblem(response, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1_900)).isLessThan(Duration.ofMillis(2_900));
        assertUnchanged(orderId, p, code);
    }

    @Test
    @DisplayName("R5.7 totalPrice가 0이면 PG를 호출하지 않고 바로 PAID")
    void zeroTotalSkipsGateway() {
        long p = createProduct(5_000, 10);
        String code = createCoupon("type", "FIXED", "value", 10_000);
        long orderId = placeOrder(newUser(), code, item(p, 1)).get("id").asLong();

        ResponseEntity<JsonNode> response = pay(orderId, newKey(), "decline-card");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(response.getBody().get("paidAt").isNull()).isFalse();
        assertThat(PG.paymentCallsFor(orderId)).isEmpty();
        assertThat(product(p).get("stock").asInt()).isEqualTo(9);
        assertThat(product(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R5.2 PENDING_PAYMENT가 아니면 409 INVALID_STATE, 없는 주문은 404")
    void invalidState() {
        long p = createProduct(5_000, 10);
        long paid = paidOrder(newUser(), null, item(p, 1)).get("id").asLong();
        assertProblem(pay(paid, newKey(), "card-ok"), 409, "INVALID_STATE");

        long cancelled = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        post("/api/orders/" + cancelled + "/cancel", null);
        assertProblem(pay(cancelled, newKey(), "card-ok"), 409, "INVALID_STATE");

        long failed = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        pay(failed, newKey(), "decline-card");
        assertProblem(pay(failed, newKey(), "card-ok"), 409, "INVALID_STATE");

        assertThat(PG.paymentCallsFor(paid)).hasSize(1);
        assertThat(PG.paymentCallsFor(cancelled)).isEmpty();
        assertProblem(pay(999_999_999L, newKey(), "card-ok"), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R5.1 cardToken이 없거나 공백이면 400")
    void invalidCardToken() {
        long p = createProduct(5_000, 10);
        long orderId = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        assertProblem(pay(orderId, newKey(), "   "), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/" + orderId + "/pay", map(), "Idempotency-Key", newKey()), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/" + orderId + "/pay", "{", "Idempotency-Key", newKey()), 400, "VALIDATION_ERROR");
        assertThat(PG.paymentCallsFor(orderId)).isEmpty();
    }

    private void assertUnchanged(long orderId, long productId, String couponCode) {
        JsonNode order = order(orderId);
        assertThat(order.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("paidAt").isNull()).isTrue();
        assertThat(product(productId).get("stock").asInt()).isEqualTo(10);
        assertThat(product(productId).get("reserved").asInt()).isEqualTo(4);
        assertThat(coupon(couponCode).get("usedCount").asInt()).isEqualTo(1);
    }
}
