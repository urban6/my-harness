package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R5. 결제 */
class PaymentTest extends IntegrationTestBase {

    @Test
    @DisplayName("R5.1·R5.4 승인 → 200 PAID, paidAt 기록, stock·reserved 감소, PG 요청 계약 준수")
    void approved() {
        long p = product(1000, 10);
        long q = product(2000, 10);
        JsonNode created = order(items(p, 2, q, 3), null);
        long id = created.get("id").asLong();
        String key = "pay-" + uniq();

        Res r = pay(id, "tok_visa", key);

        assertThat(r.status()).isEqualTo(200);
        JsonNode paid = r.json();
        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("paidAt").isNull()).isFalse();
        assertThat(Instant.parse(paid.get("paidAt").asText())).isBetween(Instant.now().minusSeconds(30), Instant.now());
        assertThat(paid.get("id").asLong()).isEqualTo(id);
        assertThat(paid.get("items")).isEqualTo(created.get("items"));
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(8);
        assertThat(productOf(p).get("reserved").asLong()).isZero();
        assertThat(productOf(q).get("stock").asLong()).isEqualTo(7);
        assertThat(productOf(q).get("reserved").asLong()).isZero();
        assertThat(orderOf(id)).isEqualTo(paid);

        assertThat(GATEWAY.charges()).hasSize(1);
        FakeGateway.Received charge = GATEWAY.charges().get(0);
        assertThat(charge.method()).isEqualTo("POST");
        assertThat(charge.headers().get("idempotency-key")).isEqualTo(key);
        assertThat(charge.body().get("orderId").asLong()).isEqualTo(id);
        assertThat(charge.body().get("amount").asLong()).isEqualTo(8000);
        assertThat(charge.body().get("cardToken").asText()).isEqualTo("tok_visa");
    }

    @Test
    @DisplayName("R5.3 PG에는 할인이 반영된 totalPrice를 보낸다")
    void chargesDiscountedAmount() {
        long p = product(10_000, 10);
        String coupon = coupon("RATE", 10, 0, null, 5);
        long id = order(items(p, 1), coupon).get("id").asLong();

        assertThat(pay(id).status()).isEqualTo(200);

        assertThat(GATEWAY.charges().get(0).body().get("amount").asLong()).isEqualTo(9000);
    }

    @Test
    @DisplayName("R5.3 int 범위를 넘는 금액도 그대로 PG에 전달한다")
    void chargesAmountBeyondIntRange() {
        long p = product(10_000_000, 1000);
        long id = order(items(p, 1000), null).get("id").asLong();

        assertThat(pay(id).status()).isEqualTo(200);

        assertThat(GATEWAY.charges().get(0).body().get("amount").asLong()).isEqualTo(10_000_000_000L);
    }

    @Test
    @DisplayName("R5.1 cardToken이 비어 있거나 없으면 400이고 PG를 부르지 않는다")
    void cardTokenValidation() {
        long id = order(product(1000, 10), 1).get("id").asLong();
        String key = "pay-" + uniq();

        for (Object body : new Object[]{Map.of("cardToken", "  "), Map.of("cardToken", ""), Map.of(), "oops"}) {
            Res r = post("/api/orders/" + id + "/pay", body, "Idempotency-Key", key);
            assertThat(r.status()).as("body=%s", body).isEqualTo(400);
            assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(GATEWAY.requests()).isEmpty();
        assertThat(orderOf(id).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R5.2 없는 주문은 404 ORDER_NOT_FOUND")
    void orderMissing() {
        Res r = pay(987654321L);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R5.2 PENDING_PAYMENT가 아니면 409 INVALID_STATE (이미 결제됨)")
    void alreadyPaid() {
        long id = paidOrder(product(1000, 10), 1).get("id").asLong();
        GATEWAY.reset();

        Res r = pay(id);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(GATEWAY.charges()).isEmpty();
    }

    @Test
    @DisplayName("R5.2 취소된 주문도 409 INVALID_STATE")
    void cancelledOrder() {
        long id = order(product(1000, 10), 1).get("id").asLong();
        post("/api/orders/" + id + "/cancel", null);

        Res r = pay(id);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(GATEWAY.requests()).isEmpty();
    }

    @Test
    @DisplayName("R5.5 거절 → 402 PAYMENT_DECLINED, 주문 PAYMENT_FAILED, 예약 복원")
    void declined() {
        long p = product(1000, 10);
        long id = order(p, 4).get("id").asLong();
        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(4);

        Res r = pay(id, FakeGateway.DECLINE_TOKEN, "pay-" + uniq());

        assertThat(r.status()).isEqualTo(402);
        assertThat(r.code()).isEqualTo("PAYMENT_DECLINED");
        assertThat(r.header("Content-Type")).startsWith("application/problem+json");
        assertThat(orderOf(id).get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(orderOf(id).get("paidAt").isNull()).isTrue();
        assertThat(productOf(p).get("reserved").asLong()).isZero();
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(10);
        // 실패한 주문은 다시 결제할 수 없다.
        assertThat(pay(id).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("R5.6 PG가 5xx를 주면 503 PAYMENT_GATEWAY_UNAVAILABLE, 주문·재고는 그대로")
    void gatewayServerError() {
        long p = product(1000, 10);
        long id = order(p, 2).get("id").asLong();
        GATEWAY.mode(FakeGateway.Mode.SERVER_ERROR);

        Res r = pay(id);

        assertUnavailableAndUntouched(r, p, id);
    }

    @Test
    @DisplayName("R5.6 연결이 끊기면 503")
    void gatewayDropsConnection() {
        long p = product(1000, 10);
        long id = order(p, 2).get("id").asLong();
        GATEWAY.mode(FakeGateway.Mode.DROP_CONNECTION);

        assertUnavailableAndUntouched(pay(id), p, id);
    }

    @Test
    @DisplayName("R5.6 PG가 2초 안에 응답하지 않으면 503 (약 2초 뒤)")
    void gatewayTimeout() {
        long p = product(1000, 10);
        long id = order(p, 2).get("id").asLong();
        GATEWAY.mode(FakeGateway.Mode.HANG);

        long start = System.nanoTime();
        Res r = pay(id);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertUnavailableAndUntouched(r, p, id);
        assertThat(elapsed).isBetween(Duration.ofMillis(1800), Duration.ofMillis(3500));
    }

    @Test
    @DisplayName("R5.6 PG 장애 뒤 복구되면 같은 주문을 다시 결제할 수 있다")
    void gatewayRecovers() {
        long p = product(1000, 10);
        long id = order(p, 2).get("id").asLong();
        GATEWAY.mode(FakeGateway.Mode.SERVER_ERROR);
        assertThat(pay(id).status()).isEqualTo(503);

        GATEWAY.mode(FakeGateway.Mode.NORMAL);
        Res r = pay(id);

        assertThat(r.status()).isEqualTo(200);
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(8);
    }

    private void assertUnavailableAndUntouched(Res r, long productId, long orderId) {
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(orderOf(orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(productOf(productId).get("stock").asLong()).isEqualTo(10);
        assertThat(productOf(productId).get("reserved").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("R5.7 totalPrice가 0이면 PG를 부르지 않고 바로 승인")
    void freeOrderSkipsGateway() {
        long p = product(1000, 10);
        String coupon = coupon("RATE", 100, 0, null, 5);
        JsonNode created = order(items(p, 2), coupon);
        assertThat(created.get("totalPrice").asLong()).isZero();

        Res r = pay(created.get("id").asLong());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asText()).isEqualTo("PAID");
        assertThat(r.json().get("paidAt").isNull()).isFalse();
        assertThat(GATEWAY.requests()).isEmpty();
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(8);
        assertThat(productOf(p).get("reserved").asLong()).isZero();
    }

    @Test
    @DisplayName("R5.7 0원 주문도 PG 장애와 무관하게 승인되고, 취소(환불)해도 PG를 부르지 않는다")
    void freeOrderIgnoresGatewayOutage() {
        long p = product(1000, 10);
        String coupon = coupon("RATE", 100, 0, null, 5);
        long id = order(items(p, 1), coupon).get("id").asLong();
        GATEWAY.mode(FakeGateway.Mode.SERVER_ERROR);

        assertThat(pay(id).status()).isEqualTo(200);
        Res cancel = post("/api/orders/" + id + "/cancel", null);

        assertThat(cancel.status()).isEqualTo(200);
        assertThat(cancel.json().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(10);
        assertThat(couponOf(coupon).get("usedCount").asLong()).isZero();
    }
}
