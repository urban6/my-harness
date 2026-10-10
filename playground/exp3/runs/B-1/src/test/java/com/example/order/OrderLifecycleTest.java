package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class OrderLifecycleTest extends IntegrationTest {

    // ---------------------------------------------------------------- 결제

    @Test
    void pay_approved_marksPaid_commitsStock_andSendsAmountToGateway() throws Exception {
        long p = createProduct("A", 1000, 10);
        JsonNode o = order(1, null, p, 3);
        long id = o.get("id").asLong();

        JsonNode paid = json(pay(id, "pay-1", "tok_ok").andExpect(status().isOk()));

        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("paidAt").isNull()).isFalse();
        assertThat(product(p).get("stock").asInt()).isEqualTo(7);
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(GATEWAY.chargeCalls.get()).isEqualTo(1);
        assertThat(GATEWAY.chargedAmounts).containsExactly(3000L);
    }

    @Test
    void pay_chargesDiscountedTotal() throws Exception {
        long p = createProduct("A", 10000, 10);
        String code = createCoupon("FIXED", 2000, 0, null, 10);
        long id = order(1, code, p, 1).get("id").asLong();

        paid(id);

        assertThat(GATEWAY.chargedAmounts).containsExactly(8000L);
    }

    @Test
    void pay_sameIdempotencyKey_doesNotChargeTwice() throws Exception {
        long p = createProduct("A", 1000, 10);
        long id = order(1, null, p, 1).get("id").asLong();

        JsonNode first = json(pay(id, "pay-dup", "tok_ok").andExpect(status().isOk()));
        JsonNode second = json(pay(id, "pay-dup", "tok_ok").andExpect(status().isOk()));

        assertThat(second.get("status").asText()).isEqualTo("PAID");
        assertThat(second.get("paidAt").asText()).isEqualTo(first.get("paidAt").asText());
        assertThat(GATEWAY.chargeCalls.get()).isEqualTo(1);
        assertThat(product(p).get("stock").asInt()).isEqualTo(9);
    }

    @Test
    void pay_differentKeyOnPaidOrder_returns409() throws Exception {
        long p = createProduct("A", 1000, 10);
        long id = order(1, null, p, 1).get("id").asLong();
        pay(id, "pay-a", "tok_ok").andExpect(status().isOk());

        pay(id, "pay-b", "tok_ok").andExpect(status().isConflict());
        assertThat(GATEWAY.chargeCalls.get()).isEqualTo(1);
    }

    @Test
    void pay_declined_marksPaymentFailed_andReleasesStockAndCoupon() throws Exception {
        long p = createProduct("A", 1000, 10);
        String code = createCoupon("FIXED", 100, 0, null, 5);
        long id = order(1, code, p, 4).get("id").asLong();
        assertThat(product(p).get("available").asInt()).isEqualTo(6);

        JsonNode failed = json(pay(id, "pay-d", "tok_decline").andExpect(status().isOk()));

        assertThat(failed.get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(product(p).get("available").asInt()).isEqualTo(10);
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        pay(id, "pay-d2", "tok_ok").andExpect(status().isConflict());
    }

    @Test
    void pay_gatewayError_returns502_keepsOrderPending_andAllowsRetry() throws Exception {
        long p = createProduct("A", 1000, 10);
        long id = order(1, null, p, 1).get("id").asLong();

        pay(id, "pay-e", "tok_error").andExpect(status().isBadGateway());

        assertThat(getOrder(id).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);

        JsonNode retried = json(pay(id, "pay-e", "tok_ok").andExpect(status().isOk()));
        assertThat(retried.get("status").asText()).isEqualTo("PAID");
        // PG 멱등 키는 주문당 하나라서 재시도해도 PG 가 중복 결제하지 않는다
        assertThat(GATEWAY.chargeKeys).containsOnly("order-" + id);
    }

    @Test
    void pay_returns409_whenExpired_andReleasesReservation() throws Exception {
        long p = createProduct("A", 1000, 10);
        long id = order(1, null, p, 2).get("id").asLong();
        clock.advance(Duration.ofMinutes(16));

        pay(id, "pay-x", "tok_ok").andExpect(status().isConflict());

        assertThat(getOrder(id).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(p).get("available").asInt()).isEqualTo(10);
        assertThat(GATEWAY.chargeCalls.get()).isZero();
    }

    @Test
    void pay_returns400_withoutIdempotencyKeyOrCardToken() throws Exception {
        long p = createProduct("A", 1000, 10);
        long id = order(1, null, p, 1).get("id").asLong();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders/" + id + "/pay")
                        .contentType("application/json").content("{\"cardToken\":\"t\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders/" + id + "/pay")
                        .header("Idempotency-Key", "k").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pay_returns404_whenOrderMissing() throws Exception {
        pay(999999, "k", "tok_ok").andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- 취소 / 환불

    @Test
    void cancel_pending_releasesStockAndCoupon_withoutCallingGateway() throws Exception {
        long p = createProduct("A", 1000, 10);
        String code = createCoupon("FIXED", 100, 0, null, 5);
        long id = order(1, code, p, 3).get("id").asLong();

        JsonNode cancelled = json(action(id, "cancel").andExpect(status().isOk()));

        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(product(p).get("available").asInt()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        assertThat(GATEWAY.refundCalls.get()).isZero();
        action(id, "cancel").andExpect(status().isConflict());
    }

    @Test
    void cancel_paid_refundsThroughGateway_andRestoresStockAndCoupon() throws Exception {
        long p = createProduct("A", 1000, 10);
        String code = createCoupon("FIXED", 100, 0, null, 5);
        long id = order(1, code, p, 3).get("id").asLong();
        paid(id);
        assertThat(product(p).get("stock").asInt()).isEqualTo(7);

        JsonNode refunded = json(action(id, "cancel").andExpect(status().isOk()));

        assertThat(refunded.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(GATEWAY.refundCalls.get()).isEqualTo(1);
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(product(p).get("available").asInt()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
    }

    @Test
    void cancel_paid_whenRefundFails_returns502_andStaysPaid() throws Exception {
        long p = createProduct("A", 1000, 10);
        long id = order(1, null, p, 1).get("id").asLong();
        paid(id);
        GATEWAY.refundFails = true;

        action(id, "cancel").andExpect(status().isBadGateway());

        assertThat(getOrder(id).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asInt()).isEqualTo(9);

        GATEWAY.refundFails = false;
        assertThat(json(action(id, "cancel").andExpect(status().isOk())).get("status").asText()).isEqualTo("REFUNDED");
    }

    @Test
    void cancel_returns409_afterShipped_orFailedOrExpired() throws Exception {
        long p = createProduct("A", 1000, 10);

        long shipped = order(1, null, p, 1).get("id").asLong();
        paid(shipped);
        action(shipped, "ship").andExpect(status().isOk());
        action(shipped, "cancel").andExpect(status().isConflict());

        long failed = order(1, null, p, 1).get("id").asLong();
        pay(failed, UUID.randomUUID().toString(), "tok_decline").andExpect(status().isOk());
        action(failed, "cancel").andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- 배송

    @Test
    void ship_andDeliver_followThePaidShippedDeliveredOrder() throws Exception {
        long p = createProduct("A", 1000, 10);
        long id = order(1, null, p, 1).get("id").asLong();

        action(id, "ship").andExpect(status().isConflict());           // 결제 전
        paid(id);
        action(id, "deliver").andExpect(status().isConflict());        // 발송 전

        assertThat(json(action(id, "ship").andExpect(status().isOk())).get("status").asText()).isEqualTo("SHIPPED");
        action(id, "ship").andExpect(status().isConflict());
        assertThat(json(action(id, "deliver").andExpect(status().isOk())).get("status").asText()).isEqualTo("DELIVERED");
        action(id, "deliver").andExpect(status().isConflict());
        assertThat(getOrder(id).get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void ship_returns404_whenMissing() throws Exception {
        action(999999, "ship").andExpect(status().isNotFound());
        action(999999, "deliver").andExpect(status().isNotFound());
        action(999999, "cancel").andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- 만료

    @Test
    void get_afterTtl_showsExpired_andReleasesStockAndCoupon() throws Exception {
        long p = createProduct("A", 1000, 10);
        String code = createCoupon("FIXED", 100, 0, null, 5);
        long id = order(1, code, p, 4).get("id").asLong();
        assertThat(product(p).get("available").asInt()).isEqualTo(6);

        clock.advance(Duration.ofMinutes(14));
        assertThat(getOrder(id).get("status").asText()).isEqualTo("PENDING_PAYMENT");

        clock.advance(Duration.ofMinutes(2));
        assertThat(getOrder(id).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(p).get("available").asInt()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        action(id, "cancel").andExpect(status().isConflict());
    }

    @Test
    void scheduler_expiresDueOrders_withoutAnyRequest() throws Exception {
        long p = createProduct("A", 1000, 10);
        long id = order(1, null, p, 2).get("id").asLong();

        clock.advance(Duration.ofMinutes(16));

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(product(p).get("available").asInt()).isEqualTo(10));
        assertThat(getOrder(id).get("status").asText()).isEqualTo("EXPIRED");
    }
}
