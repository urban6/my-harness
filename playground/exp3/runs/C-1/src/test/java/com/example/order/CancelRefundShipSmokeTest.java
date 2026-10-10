package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.PaymentGatewayStub.Behavior;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class CancelRefundShipSmokeTest extends AbstractIntegrationTest {

    @Test
    void cancel_pendingOrder_releasesStockAndCouponWithoutCallingGateway() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        api.createCoupon("C1", "FIXED", 100, null, null, 2);
        long orderId = api.createOrder("u", "k1", "C1", product, 2).get("id").asLong();

        api.action(orderId, "cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(reserved(product)).isZero();
        assertThat(couponUsed("C1")).isZero();
        assertThat(GATEWAY.requests()).isEmpty();

        api.action(orderId, "cancel")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:invalid-order-state"));
        assertThat(reserved(product)).isZero(); // no double release
    }

    @Test
    void cancel_paidOrder_refundsViaGatewayAndReleasesOnce() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        long orderId = api.createPaidOrder("u", "k1", null, product, 2);

        api.action(orderId, "cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        assertThat(GATEWAY.refunds()).hasSize(1);
        assertThat(GATEWAY.refunds().get(0).path()).matches("/v1/payments/pg-\\d+/refund");
        assertThat(reserved(product)).isZero();

        api.action(orderId, "cancel").andExpect(status().isConflict());
        assertThat(GATEWAY.refunds()).hasSize(1); // no double refund
    }

    @Test
    void cancel_paidOrder_gatewayFailure_returns502KeepsPaidAndRetryRefunds() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        long orderId = api.createPaidOrder("u", "k1", null, product, 2);
        GATEWAY.refundBehavior(Behavior.SERVER_ERROR);

        api.action(orderId, "cancel")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:pg-gateway-error"));
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(reserved(product)).isEqualTo(2);

        GATEWAY.refundBehavior(Behavior.OK);
        api.action(orderId, "cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(reserved(product)).isZero();
    }

    @Test
    void shipThenDeliver_confirmsStockAndAdvancesStatus() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        long orderId = api.createPaidOrder("u", "k1", null, product, 2);

        api.action(orderId, "deliver")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:invalid-order-state"))
                .andExpect(jsonPath("$.action").value("deliver"));

        api.action(orderId, "ship")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
        assertThat(stock(product)).isEqualTo(3);
        assertThat(reserved(product)).isZero();
        api.getProductAvailable(product, 3);

        api.action(orderId, "ship").andExpect(status().isConflict());
        api.action(orderId, "cancel")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:invalid-order-state"));

        api.action(orderId, "deliver")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        api.action(orderId, "deliver").andExpect(status().isConflict());
    }

    @Test
    void ship_pendingOrderOrUnknownOrder_returns409Or404() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        long orderId = api.createOrder("u", "k1", null, product, 1).get("id").asLong();
        api.action(orderId, "ship").andExpect(status().isConflict());
        api.action(123456, "ship")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:order-not-found"));
        api.action(123456, "cancel").andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- R13 / R14 additions

    @Test
    @DisplayName("R13 refunding a PAID multi-item order with a coupon returns every product reservation and the coupon use exactly once")
    void cancel_paidMultiItemOrderWithCoupon_releasesEverythingOnce() throws Exception {
        long a = api.createProduct("A", 1000, 10);
        long b = api.createProduct("B", 2000, 10);
        api.createCoupon("C1", "FIXED", 100, null, null, 3);
        long orderId = api.createPaidOrder("u", "k1", "C1", a, 2, b, 3);
        assertThat(reserved(a)).isEqualTo(2);
        assertThat(reserved(b)).isEqualTo(3);
        assertThat(couponUsed("C1")).isEqualTo(1);

        api.action(orderId, "cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());

        assertThat(reserved(a)).isZero();
        assertThat(reserved(b)).isZero();
        assertThat(stock(a)).isEqualTo(10);
        assertThat(couponUsed("C1")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, orderId)).isEqualTo("REFUNDED");
        assertThat(GATEWAY.refunds()).hasSize(1);
        api.getProductAvailable(a, 10);
    }

    @Test
    @DisplayName("R13 the refund goes to /v1/payments/{pgPaymentId}/refund of the id stored from the charge")
    void cancel_paidOrder_refundsTheStoredPaymentId() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        long orderId = api.createPaidOrder("u", "k1", null, product, 1);
        String pgPaymentId = jdbc.queryForObject("SELECT pg_payment_id FROM payments WHERE order_id = ?", String.class, orderId);

        api.action(orderId, "cancel").andExpect(status().isOk());

        assertThat(GATEWAY.refunds()).hasSize(1);
        assertThat(GATEWAY.refunds().get(0).method()).isEqualTo("POST");
        assertThat(GATEWAY.refunds().get(0).path()).isEqualTo("/v1/payments/" + pgPaymentId + "/refund");
    }

    @Test
    @DisplayName("R13 cancel in SHIPPED, DELIVERED, REFUNDED, EXPIRED and PAYMENT_FAILED is 409 and never calls the PG refund")
    void cancel_inNonCancellableStates_returns409WithoutRefund() throws Exception {
        long product = api.createProduct("Desk", 1000, 50);
        long shipped = api.createPaidOrder("u", "s", null, product, 1);
        api.action(shipped, "ship").andExpect(status().isOk());
        long delivered = api.createPaidOrder("u", "d", null, product, 1);
        api.action(delivered, "ship").andExpect(status().isOk());
        api.action(delivered, "deliver").andExpect(status().isOk());
        long refunded = api.createPaidOrder("u", "r", null, product, 1);
        api.action(refunded, "cancel").andExpect(status().isOk());
        long expired = api.createOrder("u", "e", null, product, 1).get("id").asLong();
        long failed = api.createOrder("u", "f", null, product, 1).get("id").asLong();
        GATEWAY.chargeBehavior(Behavior.DECLINE);
        api.pay(failed, "pay-f", "tok").andExpect(jsonPath("$.status").value("PAYMENT_FAILED"));
        int refundsBefore = GATEWAY.refunds().size();
        clock.advance(java.time.Duration.ofMinutes(16));
        api.getOrder(expired).andExpect(jsonPath("$.status").value("EXPIRED"));

        for (long id : new long[] {shipped, delivered, refunded, failed}) {
            api.action(id, "cancel").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:problem:order-payment:invalid-order-state"));
        }
        api.action(expired, "cancel").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:order-expired"));
        assertThat(GATEWAY.refunds()).hasSize(refundsBefore);
    }

    @Test
    @DisplayName("R14 ship confirms stock for every item (stock and reserved both drop), keeps the coupon use, and does not call the PG")
    void ship_multiItemOrder_confirmsStockPerItem() throws Exception {
        long a = api.createProduct("A", 1000, 10);
        long b = api.createProduct("B", 2000, 10);
        api.createCoupon("C1", "FIXED", 100, null, null, 3);
        long orderId = api.createPaidOrder("u", "k1", "C1", a, 2, b, 3);
        int pgCalls = GATEWAY.requests().size();

        api.action(orderId, "ship").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));

        assertThat(stock(a)).isEqualTo(8);
        assertThat(stock(b)).isEqualTo(7);
        assertThat(reserved(a)).isZero();
        assertThat(reserved(b)).isZero();
        api.getProductAvailable(a, 8);
        api.getProductAvailable(b, 7);
        assertThat(couponUsed("C1")).isEqualTo(1);
        assertThat(GATEWAY.requests()).hasSize(pgCalls);
    }

    @Test
    @DisplayName("R14 deliver on PENDING_PAYMENT, PAID and on unknown ids: 409, 409, 404; ship/deliver never touch stock then")
    void deliver_wrongStateOrUnknown() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        long pending = api.createOrder("u", "k1", null, product, 1).get("id").asLong();
        long paid = api.createPaidOrder("u", "k2", null, product, 1);

        api.action(pending, "deliver").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:invalid-order-state"))
                .andExpect(jsonPath("$.currentStatus").value("PENDING_PAYMENT"));
        api.action(paid, "deliver").andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("PAID"));
        api.action(999_001, "deliver").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:order-not-found"));
        assertThat(stock(product)).isEqualTo(5);
        assertThat(reserved(product)).isEqualTo(2);
    }

    @Test
    @DisplayName("R14 a shipped order cannot be shipped again and a second ship does not take stock twice")
    void ship_twice_doesNotDoubleDeductStock() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        long orderId = api.createPaidOrder("u", "k1", null, product, 2);
        api.action(orderId, "ship").andExpect(status().isOk());

        api.action(orderId, "ship").andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("SHIPPED"));
        assertThat(stock(product)).isEqualTo(3);
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("SHIPPED")).andExpect(jsonPath("$.paidAt").isNotEmpty());
    }
}
