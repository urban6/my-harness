package com.example.order;

import com.example.order.support.Api.Resp;
import com.example.order.support.FakePaymentGateway.Behavior;
import com.example.order.support.FakePaymentGateway.PaymentRequest;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R5 결제")
class R05PaymentTest extends IntegrationTest {

    @Test
    @DisplayName("R5.3·R5.4 승인되면 200 PAID, paidAt 기록, stock·reserved 감소, PG에 키·금액·카드 토큰 전달")
    void approved() {
        long p = createProduct(5000, 10);
        long q = createProduct(1000, 10);
        createCoupon(couponBody("PAY1000", "FIXED", 1000));
        long orderId = placeOrderOk("u1", "PAY1000", p, 2, q, 1);
        String key = newKey();

        Resp resp = pay(orderId, key, "tok_visa_4242");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode order = resp.json();
        assertThat(order.path("status").asText()).isEqualTo("PAID");
        assertThat(order.path("paidAt").isNull()).isFalse();
        assertThat(OffsetDateTime.parse(order.path("paidAt").asText()))
                .isAfterOrEqualTo(OffsetDateTime.parse(order.path("createdAt").asText()));
        assertThat(order(orderId)).isEqualTo(order);

        assertThat(product(p).path("stock").asInt()).isEqualTo(8);
        assertThat(product(p).path("reserved").asInt()).isZero();
        assertThat(product(q).path("stock").asInt()).isEqualTo(9);
        assertThat(product(q).path("reserved").asInt()).isZero();
        assertThat(coupon("PAY1000").path("usedCount").asInt()).isEqualTo(1);

        assertThat(PG.paymentRequests()).hasSize(1);
        PaymentRequest sent = PG.paymentRequests().get(0);
        assertThat(sent.idempotencyKey()).isEqualTo(key);
        assertThat(sent.body().path("orderId").asLong()).isEqualTo(orderId);
        assertThat(sent.body().path("amount").asLong()).isEqualTo(10000);
        assertThat(sent.body().path("cardToken").asText()).isEqualTo("tok_visa_4242");
    }

    @Test
    @DisplayName("R5.5 거절되면 402, PAYMENT_FAILED, 예약·쿠폰 사용 복원")
    void declined() {
        long p = createProduct(5000, 10);
        createCoupon(couponBody("DECL", "FIXED", 1000));
        long orderId = placeOrderOk("u1", "DECL", p, 3);
        PG.paymentBehavior(Behavior.DECLINE);

        assertProblem(pay(orderId), 402, "PAYMENT_DECLINED");

        assertThat(order(orderId).path("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(order(orderId).get("paidAt").isNull()).isTrue();
        assertThat(product(p).path("stock").asInt()).isEqualTo(10);
        assertThat(product(p).path("reserved").asInt()).isZero();
        assertThat(coupon("DECL").path("usedCount").asInt()).isZero();
        assertThat(placeOrder("u1", "DECL", p, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R5.6 PG 5xx면 503, 주문·재고·쿠폰은 그대로")
    void gatewayServerError() {
        assertGatewayFailureLeavesStateUnchanged(() -> PG.paymentBehavior(Behavior.SERVER_ERROR));
    }

    @Test
    @DisplayName("R5.6 PG 연결 실패면 503, 주문·재고·쿠폰은 그대로")
    void gatewayConnectionFailure() {
        assertGatewayFailureLeavesStateUnchanged(() -> PG.paymentBehavior(Behavior.DROP_CONNECTION));
    }

    @Test
    @DisplayName("R5.6 PG가 2초 안에 응답하지 않으면 503, 주문·재고·쿠폰은 그대로")
    void gatewayTimeout() {
        long started = System.nanoTime();
        assertGatewayFailureLeavesStateUnchanged(() -> PG.paymentDelayMs(3500));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3500);
    }

    @Test
    @DisplayName("R5.6 PG 장애 뒤 같은 주문을 다시 결제할 수 있다")
    void retryAfterGatewayFailure() {
        long p = createProduct(5000, 10);
        long orderId = placeOrderOk("u1", null, p, 1);
        PG.paymentBehavior(Behavior.SERVER_ERROR);
        assertProblem(pay(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        PG.paymentBehavior(Behavior.APPROVE);
        assertThat(pay(orderId).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R5.7 totalPrice가 0이면 PG를 부르지 않고 PAID")
    void zeroTotalSkipsGateway() {
        long p = createProduct(3000, 10);
        createCoupon(couponBody("FREE", "RATE", 100));
        long orderId = placeOrderOk("u1", "FREE", p, 2);

        Resp resp = pay(orderId);

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.json().path("status").asText()).isEqualTo("PAID");
        assertThat(resp.json().path("totalPrice").asLong()).isZero();
        assertThat(PG.paymentRequests()).isEmpty();
        assertThat(product(p).path("stock").asInt()).isEqualTo(8);
        assertThat(product(p).path("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R5.2 PENDING_PAYMENT가 아니면 409, 없는 주문은 404")
    void invalidStateAndNotFound() {
        long p = createProduct(1000, 10);
        long paid = paidOrder("u1", null, p, 1);
        long cancelled = placeOrderOk("u1", null, p, 1);
        api.post("/api/orders/" + cancelled + "/cancel", null);

        assertProblem(pay(paid), 409, "INVALID_STATE");
        assertProblem(pay(cancelled), 409, "INVALID_STATE");
        assertProblem(pay(777_777), 404, "ORDER_NOT_FOUND");
        assertThat(PG.paymentRequests()).hasSize(1);
    }

    @Test
    @DisplayName("R5.1 cardToken이 비었거나 없으면 400")
    void cardTokenRequired() {
        long p = createProduct(1000, 10);
        long orderId = placeOrderOk("u1", null, p, 1);
        assertProblem(pay(orderId, newKey(), "  "), 400, "VALIDATION_ERROR");
        assertProblem(api.post("/api/orders/" + orderId + "/pay", Map.of(), "Idempotency-Key", newKey()),
                400, "VALIDATION_ERROR");
        assertProblem(api.post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "c"), "Idempotency-Key", "k".repeat(65)),
                400, "VALIDATION_ERROR");
        assertThat(PG.paymentRequests()).isEmpty();
    }

    private void assertGatewayFailureLeavesStateUnchanged(Runnable breakGateway) {
        long p = createProduct(5000, 10);
        createCoupon(couponBody("SAFE", "FIXED", 1000));
        long orderId = placeOrderOk("u1", "SAFE", p, 2);
        JsonNode before = order(orderId);
        breakGateway.run();

        assertProblem(pay(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(order(orderId)).isEqualTo(before);
        assertThat(product(p).path("stock").asInt()).isEqualTo(10);
        assertThat(product(p).path("reserved").asInt()).isEqualTo(2);
        assertThat(coupon("SAFE").path("usedCount").asInt()).isEqualTo(1);
    }
}
