package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.PaymentGatewayStub.Behavior;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** R11: PG outages on charge and refund, state consistency, retry with the same key, no DB lock held during the PG call. */
class PaymentGatewayFailureTest extends AbstractIntegrationTest {

    private long product;

    private long pendingOrder(String key, String coupon) throws Exception {
        if (product == 0) {
            product = api.createProduct("Laptop", 20000, 10);
        }
        return api.createOrder("user-1", key, coupon, product, 2).get("id").asLong();
    }

    private Map<String, Object> payment(long orderId) {
        return jdbc.queryForMap("SELECT status, attempt_count, last_error, pg_payment_id FROM payments WHERE order_id = ?", orderId);
    }

    private boolean leaseCleared(long orderId) {
        return jdbc.queryForObject("SELECT lease_token IS NULL AND lease_kind IS NULL AND lease_expires_at IS NULL FROM orders WHERE id = ?",
                Boolean.class, orderId);
    }

    // ------------------------------------------------------------------ charge

    @Test
    @DisplayName("R11 charge read-timeout -> 504 within the configured timeout, order stays PENDING_PAYMENT, nothing released")
    void charge_timeout_returns504AndKeepsPending() throws Exception {
        api.createCoupon("C1", "FIXED", 100, null, null, 3);
        long orderId = pendingOrder("o1", "C1");
        GATEWAY.delayMillis(2500); // read-timeout is PT1S in tests

        long startedAt = System.nanoTime();
        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:pg-gateway-timeout"))
                .andExpect(jsonPath("$.status").value(504))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.retryable").value(true));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMillis).as("the read timeout, not the slow PG, ended the call").isLessThan(2400);
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PENDING_PAYMENT")).andExpect(jsonPath("$.paidAt").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT paid_at IS NULL FROM orders WHERE id = ?", Boolean.class, orderId)).isTrue();
        assertThat(leaseCleared(orderId)).isTrue();
        assertThat(reserved(product)).isEqualTo(2);
        assertThat(couponUsed("C1")).isEqualTo(1);
        Map<String, Object> row = payment(orderId);
        assertThat(row.get("status")).isEqualTo("INITIATED");
        assertThat(row.get("last_error")).isNotNull();
        assertThat(row.get("pg_payment_id")).isNull();
    }

    @Test
    @DisplayName("R11 retry with the same Idempotency-Key after a timeout reaches the PG with the same key and can succeed")
    void charge_retryWithSameKeyAfterTimeout_succeedsAndForwardsSameKey() throws Exception {
        long orderId = pendingOrder("o1", null);
        GATEWAY.delayMillis(2500);
        api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isGatewayTimeout());

        GATEWAY.delayMillis(0);
        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(GATEWAY.charges()).hasSize(2);
        assertThat(GATEWAY.charges()).allSatisfy(c -> assertThat(c.headers().get("idempotency-key")).isEqualTo("pay-1"));
        assertThat(payment(orderId).get("attempt_count")).isEqualTo(2);
        assertThat(payment(orderId).get("status")).isEqualTo("APPROVED");
        assertThat(leaseCleared(orderId)).isTrue();
    }

    @ParameterizedTest(name = "R11 charge failure mode {0} -> 502, order PENDING_PAYMENT, same-key retry possible")
    @EnumSource(value = Behavior.class, names = {"SERVER_ERROR", "CLIENT_ERROR", "MALFORMED", "UNKNOWN_STATUS", "DROP"})
    void charge_gatewayFailureModes_return502AndKeepPending(Behavior mode) throws Exception {
        long orderId = pendingOrder("o1", null);
        GATEWAY.chargeBehavior(mode);

        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:pg-gateway-error"))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.retryable").value(true));

        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        assertThat(leaseCleared(orderId)).isTrue();
        assertThat(reserved(product)).isEqualTo(2);
        assertThat(payment(orderId).get("status")).isEqualTo("INITIATED");

        GATEWAY.chargeBehavior(Behavior.OK);
        api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @DisplayName("R11 charge with the PG unreachable (connection refused) -> 502 and the order stays PENDING_PAYMENT")
    void charge_connectionRefused_returns502() throws Exception {
        long orderId = pendingOrder("o1", null);
        GATEWAY.refuseConnections();

        api.pay(orderId, "pay-1", "tok_visa")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:pg-gateway-error"));

        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        assertThat(leaseCleared(orderId)).isTrue();
        GATEWAY.acceptConnections();
        api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @DisplayName("R11 after a gateway failure a different Idempotency-Key or a different cardToken is rejected with 409")
    void charge_afterGatewayFailure_otherKeyOrOtherCardIsRejected() throws Exception {
        long orderId = pendingOrder("o1", null);
        GATEWAY.chargeBehavior(Behavior.SERVER_ERROR);
        api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isBadGateway());
        GATEWAY.chargeBehavior(Behavior.OK);

        api.pay(orderId, "pay-2", "tok_visa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:idempotency-key-conflict"));
        api.pay(orderId, "pay-1", "tok_other")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:idempotency-key-conflict"));
        assertThat(GATEWAY.charges()).hasSize(1);
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("R11 an order left PENDING_PAYMENT by a gateway failure can still be cancelled and releases its reservation")
    void charge_afterGatewayFailure_orderCanBeCancelled() throws Exception {
        api.createCoupon("C1", "FIXED", 100, null, null, 3);
        long orderId = pendingOrder("o1", "C1");
        GATEWAY.chargeBehavior(Behavior.SERVER_ERROR);
        api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isBadGateway());

        api.action(orderId, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(reserved(product)).isZero();
        assertThat(couponUsed("C1")).isZero();
        assertThat(GATEWAY.refunds()).isEmpty();
    }

    @Test
    @DisplayName("R11 a retry after a gateway failure that the PG then declines ends as PAYMENT_FAILED with stock and coupon returned")
    void charge_failureThenDecline_releasesOnce() throws Exception {
        api.createCoupon("C1", "FIXED", 100, null, null, 3);
        long orderId = pendingOrder("o1", "C1");
        GATEWAY.chargeBehavior(Behavior.SERVER_ERROR);
        api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isBadGateway());

        GATEWAY.chargeBehavior(Behavior.DECLINE);
        api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAYMENT_FAILED"));

        assertThat(reserved(product)).isZero();
        assertThat(couponUsed("C1")).isZero();
        assertThat(payment(orderId).get("status")).isEqualTo("DECLINED");
    }

    // ------------------------------------------------------------------ refund

    @Test
    @DisplayName("R11/R13 refund read-timeout -> 504, order stays PAID, nothing released, retry refunds")
    void refund_timeout_returns504KeepsPaidAndRetrySucceeds() throws Exception {
        api.createCoupon("C1", "FIXED", 100, null, null, 3);
        product = api.createProduct("Laptop", 20000, 10);
        long orderId = api.createPaidOrder("u", "k1", "C1", product, 2);
        GATEWAY.delayMillis(2500);

        api.action(orderId, "cancel")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:pg-gateway-timeout"))
                .andExpect(jsonPath("$.orderId").value(orderId));

        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.paidAt", notNullValue()));
        assertThat(reserved(product)).isEqualTo(2);
        assertThat(couponUsed("C1")).isEqualTo(1);
        assertThat(payment(orderId).get("status")).isEqualTo("APPROVED");
        assertThat(leaseCleared(orderId)).isTrue();

        GATEWAY.delayMillis(0);
        api.action(orderId, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(reserved(product)).isZero();
        assertThat(couponUsed("C1")).isZero();
        assertThat(payment(orderId).get("status")).isEqualTo("REFUNDED");
        assertThat(GATEWAY.refunds()).hasSize(2);
        assertThat(GATEWAY.refunds()).allSatisfy(r -> assertThat(r.headers().get("idempotency-key")).isEqualTo("refund-" + orderId));
    }

    @ParameterizedTest(name = "R11/R13 refund failure mode {0} -> 502, order stays PAID, retry possible")
    @EnumSource(value = Behavior.class, names = {"SERVER_ERROR", "CLIENT_ERROR", "MALFORMED", "UNKNOWN_STATUS", "DROP"})
    void refund_gatewayFailureModes_return502AndKeepPaid(Behavior mode) throws Exception {
        product = api.createProduct("Laptop", 20000, 10);
        long orderId = api.createPaidOrder("u", "k1", null, product, 2);
        GATEWAY.refundBehavior(mode);

        api.action(orderId, "cancel")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:pg-gateway-error"))
                .andExpect(jsonPath("$.retryable").value(true));

        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(reserved(product)).isEqualTo(2);
        assertThat(payment(orderId).get("status")).isEqualTo("APPROVED");
        assertThat(leaseCleared(orderId)).isTrue();

        GATEWAY.refundBehavior(Behavior.OK);
        api.action(orderId, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    @DisplayName("R11/R13 refund with the PG unreachable (connection refused) -> 502 and the order stays PAID")
    void refund_connectionRefused_returns502KeepsPaid() throws Exception {
        product = api.createProduct("Laptop", 20000, 10);
        long orderId = api.createPaidOrder("u", "k1", null, product, 2);
        GATEWAY.refuseConnections();

        api.action(orderId, "cancel")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:pg-gateway-error"));

        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(reserved(product)).isEqualTo(2);
        assertThat(leaseCleared(orderId)).isTrue();
    }

    @Test
    @DisplayName("R11 a failed refund leaves the order shippable (PAID) and does not block ship")
    void refund_failure_orderRemainsShippable() throws Exception {
        product = api.createProduct("Laptop", 20000, 10);
        long orderId = api.createPaidOrder("u", "k1", null, product, 2);
        GATEWAY.refundBehavior(Behavior.SERVER_ERROR);
        api.action(orderId, "cancel").andExpect(status().isBadGateway());

        api.action(orderId, "ship").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    // ------------------------------------------------------------------ no transaction / lock during the PG call

    @Test
    @DisplayName("R11 while the PG call is in flight no DB transaction is open and no order/product row is locked")
    void pgCallInFlight_holdsNoTransactionOrRowLock() throws Exception {
        long orderId = pendingOrder("o1", null);
        GATEWAY.delayMillis(800);

        CompletableFuture<Void> pay = CompletableFuture.runAsync(() -> {
            try {
                api.pay(orderId, "pay-1", "tok_visa").andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (GATEWAY.charges().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(GATEWAY.charges()).as("PG call started").hasSize(1);
        Thread.sleep(100);
        assertThat(pay).as("the request is still waiting for the PG").isNotDone();

        Integer openTransactions = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                 WHERE datname = current_database() AND pid <> pg_backend_pid() AND state LIKE 'idle in transaction%'
                """, Integer.class);
        assertThat(openTransactions).as("no application transaction is open during the PG call").isZero();
        // NOWAIT fails immediately when somebody holds a row lock
        assertThat(jdbc.queryForList("SELECT id FROM orders WHERE id = ? FOR UPDATE NOWAIT", orderId)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT id FROM products WHERE id = ? FOR UPDATE NOWAIT", product)).hasSize(1);
        // other traffic is not blocked by the in-flight payment
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        api.createOrder("user-2", "o2", null, product, 1);

        pay.get(10, TimeUnit.SECONDS);
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @DisplayName("R11/R13 while the refund PG call is in flight no DB transaction is open and the order row is not locked")
    void refundInFlight_holdsNoTransactionOrRowLock() throws Exception {
        product = api.createProduct("Laptop", 20000, 10);
        long orderId = api.createPaidOrder("u", "k1", null, product, 2);
        GATEWAY.delayMillis(800);

        CompletableFuture<Void> cancel = CompletableFuture.runAsync(() -> {
            try {
                api.action(orderId, "cancel").andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (GATEWAY.refunds().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(GATEWAY.refunds()).hasSize(1);
        Thread.sleep(100);
        assertThat(cancel).isNotDone();

        Integer openTransactions = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                 WHERE datname = current_database() AND pid <> pg_backend_pid() AND state LIKE 'idle in transaction%'
                """, Integer.class);
        assertThat(openTransactions).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM orders WHERE id = ? FOR UPDATE NOWAIT", orderId)).hasSize(1);
        // a competing cancel is told to come back instead of waiting on a lock
        api.action(orderId, "cancel")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:operation-in-progress"))
                .andExpect(header().string("Retry-After", "1"));

        cancel.get(10, TimeUnit.SECONDS);
        assertThat(GATEWAY.refunds()).hasSize(1);
    }
}
