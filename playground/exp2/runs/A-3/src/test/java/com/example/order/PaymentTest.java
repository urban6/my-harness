package com.example.order;

import com.example.order.support.FakePaymentGateway.PaymentCall;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R5. 결제")
class PaymentTest extends IntegrationTest {

    @Test
    @DisplayName("R5.3·R5.4 승인: PG 에 키·금액·카드토큰을 그대로 전달, 200 PAID, paidAt 기록, stock·reserved 감소")
    void approved() {
        long p1 = createProduct(10_000, 10);
        long p2 = createProduct(2_000, 5);
        String coupon = createCoupon("FIXED", 1_500);
        JsonNode created = placeOrder(uniqueUser(), coupon, item(p1, 2), item(p2, 1));
        long orderId = created.path("id").asLong();
        String key = uniqueKey();
        Instant before = Instant.now();

        Resp r = pay(orderId, key, "tok_card_123");

        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(r.json().path("status").asText()).isEqualTo("PAID");
        Instant paidAt = instant(r.json().path("paidAt"));
        assertThat(paidAt).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(r.json().path("totalPrice").asLong()).isEqualTo(20_500);
        assertThat(order(orderId)).isEqualTo(r.json());

        List<PaymentCall> calls = pg.paymentCallsFor(orderId);
        assertThat(calls).containsExactly(new PaymentCall(key, orderId, 20_500, "tok_card_123"));

        assertThat(product(p1).path("stock").asInt()).isEqualTo(8);
        assertThat(product(p1).path("reserved").asInt()).isZero();
        assertThat(product(p2).path("stock").asInt()).isEqualTo(4);
        assertThat(product(p2).path("reserved").asInt()).isZero();
        assertThat(coupon(coupon).path("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.5 거절: 402 PAYMENT_DECLINED, 주문 PAYMENT_FAILED, 예약·쿠폰 복원")
    void declined() {
        long productId = createProduct(1_000, 10);
        String coupon = createCoupon("FIXED", 100);
        long orderId = placeOrder(uniqueUser(), coupon, item(productId, 3)).path("id").asLong();

        assertProblem(pay(orderId, "tok_decline"), 402, "PAYMENT_DECLINED");

        assertThat(order(orderId).path("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(order(orderId).path("paidAt").isNull()).isTrue();
        assertThat(product(productId).path("stock").asInt()).isEqualTo(10);
        assertThat(product(productId).path("reserved").asInt()).isZero();
        assertThat(coupon(coupon).path("usedCount").asInt()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tok_500", "tok_drop", "tok_slow"})
    @DisplayName("R5.6 PG 5xx·연결 끊김·2초 초과면 503 PAYMENT_GATEWAY_UNAVAILABLE, 주문·재고·쿠폰 그대로")
    void gatewayUnavailable(String token) {
        long productId = createProduct(1_000, 10);
        String coupon = createCoupon("FIXED", 100);
        long orderId = placeOrder(uniqueUser(), coupon, item(productId, 3)).path("id").asLong();
        JsonNode before = order(orderId);
        Instant start = Instant.now();

        Resp r = pay(orderId, token);

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(2_900));
        assertThat(order(orderId)).isEqualTo(before);
        assertThat(product(productId).path("stock").asInt()).isEqualTo(10);
        assertThat(product(productId).path("reserved").asInt()).isEqualTo(3);
        assertThat(coupon(coupon).path("usedCount").asInt()).isEqualTo(1);

        assertThat(pay(orderId, "tok_ok").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R5.7 totalPrice 가 0 이면 PG 를 호출하지 않고 바로 PAID")
    void zeroTotalSkipsGateway() {
        long productId = createProduct(1_000, 10);
        String coupon = createCoupon("FIXED", 5_000);
        long orderId = placeOrder(uniqueUser(), coupon, item(productId, 2)).path("id").asLong();

        Resp r = pay(orderId, "tok_decline");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("status").asText()).isEqualTo("PAID");
        assertThat(r.json().path("totalPrice").asLong()).isZero();
        assertThat(r.json().path("paidAt").isNull()).isFalse();
        assertThat(pg.paymentCallsFor(orderId)).isEmpty();
        assertThat(product(productId).path("stock").asInt()).isEqualTo(8);
        assertThat(product(productId).path("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R5.2 PENDING_PAYMENT 가 아니면 409 INVALID_STATE, PG 호출 없음")
    void notPending() {
        long productId = createProduct(1_000, 10);
        long paidId = placeOrder(item(productId, 1)).path("id").asLong();
        assertThat(pay(paidId, "tok_ok").status()).isEqualTo(200);
        long cancelledId = placeOrder(item(productId, 1)).path("id").asLong();
        assertThat(post("/api/orders/" + cancelledId + "/cancel", null).status()).isEqualTo(200);

        assertProblem(pay(paidId, "tok_ok"), 409, "INVALID_STATE");
        assertProblem(pay(cancelledId, "tok_ok"), 409, "INVALID_STATE");
        assertThat(pg.paymentCallsFor(paidId)).hasSize(1);
        assertThat(pg.paymentCallsFor(cancelledId)).isEmpty();
    }

    @Test
    @DisplayName("R5.2 없는 주문은 404 ORDER_NOT_FOUND")
    void orderNotFound() {
        assertProblem(pay(987654321L, "tok_ok"), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R5.1 cardToken 누락·공백, 잘못된 JSON 은 400")
    void invalidBody() {
        long orderId = placeOrder(item(createProduct(1_000, 10), 1)).path("id").asLong();
        Map<String, String> headers = Map.of("Idempotency-Key", uniqueKey());
        assertProblem(post("/api/orders/" + orderId + "/pay", Map.of(), headers), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "  "), headers), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/" + orderId + "/pay", "{cardToken", headers), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok_ok"),
                Map.of("Idempotency-Key", "k".repeat(65))), 400, "VALIDATION_ERROR");
        assertThat(pg.paymentCallsFor(orderId)).isEmpty();
    }
}
