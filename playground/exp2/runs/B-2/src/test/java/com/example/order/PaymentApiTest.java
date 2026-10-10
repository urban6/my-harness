package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.example.order.FakePaymentGateway.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** R5. 결제 */
class PaymentApiTest extends IntegrationTestSupport {

    @Test
    void approved_returns200Paid_andConsumesStock() {
        long p = createProduct(4_000, 10);
        String code = createCoupon(Map.of("value", 1_000));
        long orderId = placeOrder(uniqueUser(), code, item(p, 3));
        String key = uniqueKey();

        Res res = post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok_visa_4242"), Map.of("Idempotency-Key", key));

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("status").asText()).isEqualTo("PAID");
        assertThat(res.body().get("paidAt").isNull()).isFalse();
        assertThat(res.body().get("totalPrice").asLong()).isEqualTo(11_000);
        assertThat(order(orderId)).isEqualTo(res.body());

        JsonNode product = product(p);
        assertThat(product.get("stock").asInt()).isEqualTo(7);
        assertThat(product.get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);

        // R5.3 PG 계약: cardToken 그대로, Idempotency-Key는 클라이언트 키 그대로
        assertThat(PG.payments()).hasSize(1);
        FakePaymentGateway.PaymentCall call = PG.payments().getFirst();
        assertThat(call.idempotencyKey()).isEqualTo(key);
        assertThat(call.body().get("orderId").asLong()).isEqualTo(orderId);
        assertThat(call.body().get("amount").asLong()).isEqualTo(11_000);
        assertThat(call.body().get("cardToken").asText()).isEqualTo("tok_visa_4242");
    }

    @Test
    void declined_returns402_andRestoresReservationAndCoupon() {
        long p = createProduct(4_000, 10);
        String code = createCoupon(Map.of());
        String user = uniqueUser();
        long orderId = placeOrder(user, code, item(p, 3));
        PG.paymentMode(Mode.DECLINE);

        assertProblem(pay(orderId, uniqueKey()), 402, "PAYMENT_DECLINED");

        assertThat(order(orderId).get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(order(orderId).get("paidAt").isNull()).isTrue();
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        assertThat(createOrder(user, code, item(p, 1)).status()).isEqualTo(201);
    }

    @Test
    void gatewayServerError_returns503_andChangesNothing() {
        assertGatewayFailureChangesNothing(Mode.SERVER_ERROR);
    }

    @Test
    void gatewayConnectionFailure_returns503_andChangesNothing() {
        assertGatewayFailureChangesNothing(Mode.DROP_CONNECTION);
    }

    @Test
    void gatewayTimeout_returns503WithinAboutTwoSeconds() {
        long started = System.nanoTime();
        assertGatewayFailureChangesNothing(Mode.SLOW);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(3_000);
    }

    private void assertGatewayFailureChangesNothing(Mode mode) {
        long p = createProduct(4_000, 10);
        String code = createCoupon(Map.of());
        long orderId = placeOrder(uniqueUser(), code, item(p, 2));
        JsonNode before = order(orderId);
        PG.paymentMode(mode);

        assertProblem(pay(orderId, uniqueKey()), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(order(orderId)).isEqualTo(before);
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    void zeroTotal_isPaidWithoutCallingGateway() {
        long p = createProduct(4_000, 10);
        String code = createCoupon(Map.of("value", 10_000));
        long orderId = placeOrder(uniqueUser(), code, item(p, 1));
        PG.paymentMode(Mode.SERVER_ERROR);

        Res res = pay(orderId, uniqueKey());

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("status").asText()).isEqualTo("PAID");
        assertThat(res.body().get("paidAt").isNull()).isFalse();
        assertThat(PG.payments()).isEmpty();
        assertThat(product(p).get("stock").asInt()).isEqualTo(9);
        assertThat(product(p).get("reserved").asInt()).isZero();
    }

    @Test
    void notPending_returns409InvalidState() {
        long orderId = paidOrder(uniqueUser(), null, item(createProduct(1_000, 10), 1));

        assertProblem(pay(orderId, uniqueKey()), 409, "INVALID_STATE");

        long cancelled = placeOrder(uniqueUser(), null, item(createProduct(1_000, 10), 1));
        post("/api/orders/" + cancelled + "/cancel");
        assertProblem(pay(cancelled, uniqueKey()), 409, "INVALID_STATE");
        assertThat(PG.payments()).hasSize(1);
    }

    @Test
    void unknownOrder_returns404() {
        assertProblem(pay(999_999_999L, uniqueKey()), 404, "ORDER_NOT_FOUND");
    }

    @Test
    void invalidRequest_returns400() {
        long orderId = placeOrder(uniqueUser(), null, item(createProduct(1_000, 10), 1));
        String path = "/api/orders/" + orderId + "/pay";

        assertProblem(post(path, Map.of("cardToken", "  "), Map.of("Idempotency-Key", uniqueKey())), 400, "VALIDATION_ERROR");
        assertProblem(post(path, "{}", Map.of("Idempotency-Key", uniqueKey())), 400, "VALIDATION_ERROR");
        assertProblem(post(path, Map.of("cardToken", "tok"), Map.of()), 400, "VALIDATION_ERROR");
        assertProblem(post(path, Map.of("cardToken", "tok"), Map.of("Idempotency-Key", "k".repeat(65))), 400, "VALIDATION_ERROR");
        assertThat(order(orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(PG.payments()).isEmpty();
    }
}
