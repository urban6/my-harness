package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.PaymentGatewayStub.Behavior;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.MediaType;

class PaymentSmokeTest extends AbstractIntegrationTest {

    private long productId;

    private long newPendingOrder(String key) throws Exception {
        if (productId == 0) {
            productId = api.createProduct("Laptop", 20000, 10);
        }
        return api.createOrder("user-1", key, null, productId, 2).get("id").asLong();
    }

    @Test
    void pay_approved_marksPaidAndSendsExpectedGatewayRequest() throws Exception {
        long orderId = newPendingOrder("o1");

        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").value(notNullValue()));

        assertThat(GATEWAY.charges()).hasSize(1);
        var call = GATEWAY.charges().get(0);
        assertThat(call.headers().get("idempotency-key")).isEqualTo("pay-1");
        JsonNode body = objectMapper.readTree(call.body());
        assertThat(body.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(body.get("amount").asLong()).isEqualTo(40000);
        assertThat(body.get("cardToken").asText()).isEqualTo("tok_visa");
        assertThat(reserved(productId)).isEqualTo(2); // reservation kept until ship
        assertThat(jdbc.queryForObject("SELECT pg_payment_id FROM payments WHERE order_id = ?", String.class, orderId))
                .startsWith("pg-");
        assertThat(jdbc.queryForObject("SELECT request_hash FROM payments WHERE order_id = ?", String.class, orderId))
                .hasSize(64).isNotEqualTo("tok_visa"); // only a SHA-256 of the card token is stored
    }

    @Test
    void pay_sameKeyAgain_replaysWithoutCallingGatewayAgain() throws Exception {
        long orderId = newPendingOrder("o1");
        api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isOk());

        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(GATEWAY.charges()).hasSize(1);

        api.pay(orderId, "pay-1", "tok_other")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:idempotency-key-conflict"));
    }

    @Test
    void pay_declined_marksPaymentFailedAndReleasesStockAndCoupon() throws Exception {
        long product = api.createProduct("Laptop", 20000, 10);
        api.createCoupon("C1", "FIXED", 1000, null, null, 3);
        long orderId = api.createOrder("user-1", "o1", "C1", product, 2).get("id").asLong();
        assertThat(reserved(product)).isEqualTo(2);
        assertThat(couponUsed("C1")).isEqualTo(1);

        GATEWAY.chargeBehavior(Behavior.DECLINE);
        api.pay(orderId, "pay-1", "tok_bad")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.paidAt").value(nullValue()));

        assertThat(reserved(product)).isZero();
        assertThat(couponUsed("C1")).isZero();
        api.pay(orderId, "pay-2", "tok_visa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:invalid-order-state"))
                .andExpect(jsonPath("$.currentStatus").value("PAYMENT_FAILED"));
    }

    @Test
    void pay_gatewayServerError_returns502KeepsPendingAndSameKeyRetrySucceeds() throws Exception {
        long orderId = newPendingOrder("o1");
        GATEWAY.chargeBehavior(Behavior.SERVER_ERROR);

        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:pg-gateway-error"))
                .andExpect(jsonPath("$.retryable").value(true));
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        assertThat(jdbc.queryForObject("SELECT lease_token IS NULL FROM orders WHERE id = ?", Boolean.class, orderId)).isTrue();

        // a different key is refused: the order is bound to the first payment key
        api.pay(orderId, "pay-2", "tok_visa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:idempotency-key-conflict"));

        GATEWAY.chargeBehavior(Behavior.OK);
        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void pay_malformedGatewayResponse_returns502() throws Exception {
        long orderId = newPendingOrder("o1");
        GATEWAY.chargeBehavior(Behavior.MALFORMED);
        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isBadGateway());
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void pay_gatewayTimeout_returns504AndKeepsPending() throws Exception {
        long orderId = newPendingOrder("o1");
        GATEWAY.delayMillis(2500); // read-timeout is PT1S in tests

        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:pg-gateway-timeout"));
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void pay_inputErrors_return400And404() throws Exception {
        long orderId = newPendingOrder("o1");
        api.pay(orderId, null, "tok")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:missing-header"));
        api.pay(orderId, "k", "  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.pay(987654, "k", "tok")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:order-not-found"));
        assertThat(GATEWAY.requests()).isEmpty();
    }

    // ---------------------------------------------------------------- R10 additions

    @Test
    @DisplayName("R10 the PG receives numeric orderId and amount = totalPrice AFTER the coupon discount, plus the client's Idempotency-Key")
    void pay_sendsDiscountedAmountAsJsonNumbers() throws Exception {
        long product = api.createProduct("Laptop", 20000, 10);
        api.createCoupon("C1", "FIXED", 5000, null, null, 3);
        long orderId = api.createOrder("user-1", "o1", "C1", product, 2).get("id").asLong();

        api.pay(orderId, "pay-77", "tok_visa").andExpect(status().isOk());

        var call = GATEWAY.charges().get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/v1/payments");
        assertThat(call.headers().get("idempotency-key")).isEqualTo("pay-77");
        JsonNode body = objectMapper.readTree(call.body());
        assertThat(body.get("orderId").isNumber()).isTrue();
        assertThat(body.get("amount").isNumber()).isTrue();
        assertThat(body.get("amount").asLong()).isEqualTo(35000);
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("orderId", "amount", "cardToken");
    }

    @Test
    @DisplayName("R10 DECLINED then the same key again: replayed PAYMENT_FAILED, the PG is not called a second time, other cards are 409")
    void pay_declinedReplay_doesNotCallGatewayAgain() throws Exception {
        long orderId = newPendingOrder("o1");
        GATEWAY.chargeBehavior(Behavior.DECLINE);
        api.pay(orderId, "pay-1", "tok_bad").andExpect(jsonPath("$.status").value("PAYMENT_FAILED"));

        GATEWAY.chargeBehavior(Behavior.OK);
        api.pay(orderId, "pay-1", "tok_bad")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"));
        api.pay(orderId, "pay-1", "tok_other")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:idempotency-key-conflict"));
        assertThat(GATEWAY.charges()).hasSize(1);
        assertThat(reserved(productId)).isZero(); // released once by the decline, not again by the replay
    }

    @Test
    @DisplayName("R10 a different key on an already PAID order is 409 invalid-order-state; the same key replays 200; the PG is called once")
    void pay_alreadyPaid_otherKeyIsRejectedSameKeyReplays() throws Exception {
        long orderId = newPendingOrder("o1");
        api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isOk());

        api.pay(orderId, "pay-2", "tok_visa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:invalid-order-state"))
                .andExpect(jsonPath("$.currentStatus").value("PAID"))
                .andExpect(jsonPath("$.action").value("pay"));
        api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(GATEWAY.charges()).hasSize(1);
    }

    @Test
    @DisplayName("R10 paying a CANCELLED order is 409 and never reaches the PG")
    void pay_cancelledOrder_returns409WithoutGatewayCall() throws Exception {
        long orderId = newPendingOrder("o1");
        api.action(orderId, "cancel").andExpect(status().isOk());

        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:invalid-order-state"))
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
        assertThat(GATEWAY.requests()).isEmpty();
    }

    @Test
    @DisplayName("R10 cardToken must be present: {} , null, empty, blank, 256 chars are 400 validation-failed; missing body is 400 malformed")
    void pay_cardTokenValidation() throws Exception {
        long orderId = newPendingOrder("o1");
        String validation = "urn:problem:order-payment:validation-failed";
        for (String json : new String[] {"{}", "{\"cardToken\":null}", "{\"cardToken\":\"\"}", "{\"cardToken\":\"   \"}",
                "{\"cardToken\":\"" + "t".repeat(256) + "\"}"}) {
            mvc.perform(post("/api/orders/" + orderId + "/pay").header("Idempotency-Key", "pay-1")
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(validation));
        }
        mvc.perform(post("/api/orders/" + orderId + "/pay").header("Idempotency-Key", "pay-1").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:malformed-request"));
        assertThat(GATEWAY.requests()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("R10 the pay Idempotency-Key must be 1..128 printable ASCII: spaces and 129 chars are 400, 128 is accepted")
    void pay_idempotencyKeyFormat() throws Exception {
        long orderId = newPendingOrder("o1");
        api.pay(orderId, "has space", "tok").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.pay(orderId, "k".repeat(129), "tok").andExpect(status().isBadRequest());
        assertThat(GATEWAY.requests()).isEmpty();
        api.pay(orderId, "k".repeat(128), "tok").andExpect(status().isOk());
        assertThat(GATEWAY.charges().get(0).headers().get("idempotency-key")).isEqualTo("k".repeat(128));
    }

    @Test
    @DisplayName("R10 payment bookkeeping: APPROVED row with pg id, paidAt persisted, the raw cardToken is stored nowhere")
    void pay_persistsPaymentWithoutRawCardToken() throws Exception {
        long orderId = newPendingOrder("o1");
        JsonNode paid = api.json(api.pay(orderId, "pay-1", "tok_super_secret_4242").andExpect(status().isOk()).andReturn());

        var row = jdbc.queryForMap("SELECT status, amount, idempotency_key, pg_payment_id, decided_at FROM payments WHERE order_id = ?", orderId);
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("amount")).isEqualTo(40000L);
        assertThat(row.get("idempotency_key")).isEqualTo("pay-1");
        assertThat(row.get("pg_payment_id")).asString().startsWith("pg-");
        assertThat(row.get("decided_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE payments::text LIKE '%tok_super_secret_4242%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE orders::text LIKE '%tok_super_secret_4242%'", Integer.class)).isZero();
        assertThat(paid.toString()).doesNotContain("tok_super_secret_4242").doesNotContain("pg-");
    }

    @Test
    @DisplayName("R10 two orders can each be paid with their own key; each triggers its own PG charge")
    void pay_twoOrders_chargeTwice() throws Exception {
        long first = newPendingOrder("o1");
        long second = newPendingOrder("o2");
        api.pay(first, "pay-a", "tok").andExpect(status().isOk());
        api.pay(second, "pay-b", "tok").andExpect(status().isOk());

        assertThat(GATEWAY.charges()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'PAID'", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("R10 a fully discounted order (totalPrice 0) is still sent to the PG with amount 0 (design 3.8)")
    void pay_zeroTotalOrder_callsGatewayWithZeroAmount() throws Exception {
        long product = api.createProduct("Freebie", 1000, 10);
        api.createCoupon("FREE", "RATE", 100, null, null, 3);
        long orderId = api.createOrder("user-1", "o1", "FREE", product, 1).get("id").asLong();

        api.pay(orderId, "pay-1", "tok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

        assertThat(objectMapper.readTree(GATEWAY.charges().get(0).body()).get("amount").asLong()).isZero();
    }
}
