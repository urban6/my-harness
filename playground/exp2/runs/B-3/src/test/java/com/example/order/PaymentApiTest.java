package com.example.order;

import static com.example.order.support.TestApi.item;
import static com.example.order.support.TestApi.newKey;
import static com.example.order.support.TestApi.newUserId;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Mode;
import com.example.order.support.FakePaymentGateway.PaymentCall;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R5 결제")
class PaymentApiTest extends IntegrationTest {

    @Test
    @DisplayName("R5.1·R5.3·R5.4 승인 → 200 PAID, paidAt 기록, stock·reserved 감소, PG에 키·토큰·금액 전달")
    void approved() {
        long productId = api.createProduct(10_000, 10);
        String code = api.createCoupon("FIXED", 1_000);
        JsonNode order = api.createOrder(newUserId(), code, List.of(item(productId, 3))).assertStatus(201).body();
        long orderId = order.get("id").asLong();
        String key = newKey();

        JsonNode paid = api.pay(orderId, key, "tok_visa_4242").assertStatus(200).body();

        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("id").asLong()).isEqualTo(orderId);
        assertThat(paid.get("totalPrice").asLong()).isEqualTo(29_000);
        assertThat(Instant.parse(paid.get("paidAt").asText())).isAfterOrEqualTo(Instant.parse(order.get("createdAt").asText()));
        assertThat(api.order(orderId)).isEqualTo(paid);

        JsonNode product = api.product(productId);
        assertThat(product.get("stock").asInt()).isEqualTo(7);
        assertThat(product.get("reserved").asInt()).isZero();
        assertThat(api.coupon(code).get("usedCount").asInt()).isEqualTo(1);

        List<PaymentCall> calls = PG.paymentCalls();
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().idempotencyKey()).isEqualTo(key);
        assertThat(calls.getFirst().body().get("orderId").asLong()).isEqualTo(orderId);
        assertThat(calls.getFirst().body().get("amount").asLong()).isEqualTo(29_000);
        assertThat(calls.getFirst().body().get("cardToken").asText()).isEqualTo("tok_visa_4242");
    }

    @Test
    @DisplayName("R5.5 거절 → 402 PAYMENT_DECLINED, PAYMENT_FAILED, 예약·쿠폰 복원")
    void declined() {
        long productId = api.createProduct(1_000, 10);
        String code = api.createCoupon("FIXED", 100);
        long orderId = api.createOrder(newUserId(), code, List.of(item(productId, 4))).assertStatus(201).id();
        PG.paymentMode(Mode.DECLINE);

        api.pay(orderId).assertProblem(402, "PAYMENT_DECLINED");

        assertThat(api.order(orderId).get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(api.order(orderId).get("paidAt").isNull()).isTrue();
        JsonNode product = api.product(productId);
        assertThat(product.get("stock").asInt()).isEqualTo(10);
        assertThat(product.get("reserved").asInt()).isZero();
        assertThat(api.coupon(code).get("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("R5.6 PG 5xx → 503, 주문·재고·쿠폰 변화 없음")
    void gatewayServerError() {
        assertUnavailableLeavesStateUnchanged(Mode.SERVER_ERROR);
    }

    @Test
    @DisplayName("R5.6 PG가 2초 안에 응답하지 않으면 503, 주문·재고·쿠폰 변화 없음")
    void gatewayTimeout() {
        long elapsed = assertUnavailableLeavesStateUnchanged(Mode.SLOW);
        assertThat(Duration.ofMillis(elapsed)).isLessThan(Duration.ofMillis(2_900));
    }

    private long assertUnavailableLeavesStateUnchanged(Mode mode) {
        long productId = api.createProduct(1_000, 10);
        String code = api.createCoupon("FIXED", 100);
        JsonNode before = api.createOrder(newUserId(), code, List.of(item(productId, 2))).assertStatus(201).body();
        long orderId = before.get("id").asLong();
        PG.paymentMode(mode);

        long start = System.nanoTime();
        api.pay(orderId).assertProblem(503, "PAYMENT_GATEWAY_UNAVAILABLE");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(api.order(orderId)).isEqualTo(before);
        assertThat(api.product(productId).get("reserved").asInt()).isEqualTo(2);
        assertThat(api.product(productId).get("stock").asInt()).isEqualTo(10);
        assertThat(api.coupon(code).get("usedCount").asInt()).isEqualTo(1);
        return elapsedMillis;
    }

    @Test
    @DisplayName("R5.7 totalPrice 0 → PG 호출 없이 PAID")
    void zeroTotal_skipsGateway() {
        long productId = api.createProduct(1_000, 10);
        String code = api.createCoupon("FIXED", 5_000);
        long orderId = api.createOrder(newUserId(), code, List.of(item(productId, 2))).assertStatus(201).id();
        PG.paymentMode(Mode.SERVER_ERROR);

        JsonNode paid = api.pay(orderId).assertStatus(200).body();

        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("totalPrice").asLong()).isZero();
        assertThat(paid.get("paidAt").isNull()).isFalse();
        assertThat(PG.paymentCalls()).isEmpty();
        assertThat(api.product(productId).get("stock").asInt()).isEqualTo(8);
    }

    @Test
    @DisplayName("R5.2 PENDING_PAYMENT가 아니면 409 INVALID_STATE, 없으면 404")
    void invalidStateOrMissing() {
        long productId = api.createProduct(1_000, 10);
        long paidId = api.createOrderId(newUserId(), productId, 1);
        api.pay(paidId).assertStatus(200);
        long cancelledId = api.createOrderId(newUserId(), productId, 1);
        api.cancel(cancelledId).assertStatus(200);

        api.pay(paidId).assertProblem(409, "INVALID_STATE");
        api.pay(cancelledId).assertProblem(409, "INVALID_STATE");
        api.pay(999_999_999L).assertProblem(404, "ORDER_NOT_FOUND");
        assertThat(PG.paymentCalls()).hasSize(1);
    }

    @Test
    @DisplayName("R5.1 cardToken 공백·누락 → 400")
    void blankCardToken_returns400() {
        long orderId = api.createOrderId(newUserId(), api.createProduct(1_000, 10), 1);
        api.pay(orderId, newKey(), "  ").assertProblem(400, "VALIDATION_ERROR");
        api.post("/api/orders/" + orderId + "/pay", Map.of(), Map.of("Idempotency-Key", newKey()))
                .assertProblem(400, "VALIDATION_ERROR");
        assertThat(PG.paymentCalls()).isEmpty();
    }
}
