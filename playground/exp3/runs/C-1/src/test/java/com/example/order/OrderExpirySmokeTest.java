package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.order.order.OrderExpirySweeper;
import com.example.order.support.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.DisplayName;
import java.time.Instant;

class OrderExpirySmokeTest extends AbstractIntegrationTest {

    @Autowired
    OrderExpirySweeper sweeper;

    @Test
    void getOrder_afterTtl_showsExpiredAndReleasesStockAndCouponExactlyOnce() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        api.createCoupon("C1", "FIXED", 100, null, null, 2);
        long orderId = api.createOrder("u", "k1", "C1", product, 2).get("id").asLong();

        clock.advance(Duration.ofMinutes(14));
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        assertThat(reserved(product)).isEqualTo(2);

        clock.advance(Duration.ofMinutes(2));
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("EXPIRED"));
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("EXPIRED"));

        assertThat(reserved(product)).isZero();
        assertThat(couponUsed("C1")).isZero();
    }

    @Test
    void payAndCancel_onExpiredOrder_return409OrderExpired() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        long orderId = api.createOrder("u", "k1", null, product, 2).get("id").asLong();
        clock.advance(Duration.ofMinutes(16));

        api.pay(orderId, "pay-1", "tok")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:order-expired"))
                .andExpect(jsonPath("$.expiresAt").exists());
        assertThat(GATEWAY.requests()).isEmpty();
        assertThat(reserved(product)).isZero(); // the lazy expiry inside pay was committed

        api.action(orderId, "cancel")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:order-expired"));
        assertThat(reserved(product)).isZero();
    }

    @Test
    void sweeper_expiresDueOrdersOnlyAndIsIdempotent() throws Exception {
        long product = api.createProduct("Desk", 1000, 10);
        long due = api.createOrder("u", "k1", null, product, 2).get("id").asLong();
        clock.advance(Duration.ofMinutes(10));
        long notDue = api.createOrder("u", "k2", null, product, 3).get("id").asLong();
        clock.advance(Duration.ofMinutes(6)); // first order is 16 min old, second 6 min

        assertThat(sweeper.sweepOnce()).isEqualTo(1);
        assertThat(sweeper.sweepOnce()).isZero();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, due)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, notDue)).isEqualTo("PENDING_PAYMENT");
        assertThat(reserved(product)).isEqualTo(3);
    }

    @Test
    void paidOrder_isNotExpiredByTtl() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        long orderId = api.createPaidOrder("u", "k1", null, product, 1);
        clock.advance(Duration.ofHours(1));
        assertThat(sweeper.sweepOnce()).isZero();
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("PAID"));
    }

    // ---------------------------------------------------------------- R12 additions

    @Test
    @DisplayName("R12 the TTL boundary: one microsecond before expiresAt the order is PENDING_PAYMENT and payable, at expiresAt it is EXPIRED")
    void expiry_boundaryIsExpiresAtInclusive() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        var first = api.createOrder("u", "k1", null, product, 1);
        var second = api.createOrder("u", "k2", null, product, 1);
        Instant expiresAt = Instant.parse(first.get("expiresAt").asText());
        assertThat(Instant.parse(second.get("expiresAt").asText())).isAfterOrEqualTo(expiresAt);

        clock.set(expiresAt.minus(Duration.ofNanos(1000)));
        api.getOrder(first.get("id").asLong()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        api.pay(first.get("id").asLong(), "pay-1", "tok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

        clock.set(Instant.parse(second.get("expiresAt").asText()));
        api.getOrder(second.get("id").asLong()).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(reserved(product)).isEqualTo(1); // only the paid order still holds stock
    }

    @Test
    @DisplayName("R12 expiry returns every item's reservation and the coupon use of a multi-item order exactly once")
    void expiry_releasesAllItemsAndCouponOnce() throws Exception {
        long a = api.createProduct("A", 1000, 10);
        long b = api.createProduct("B", 1000, 10);
        api.createCoupon("C1", "FIXED", 100, null, null, 2);
        long orderId = api.createOrder("u", "k1", "C1", a, 2, b, 4).get("id").asLong();
        api.createOrder("u", "k2", "C1", a, 1);
        // make only the first order due: push the second order's expiry one hour out
        jdbc.update("UPDATE orders SET expires_at = expires_at + interval '1 hour' WHERE idempotency_key = 'k2'");
        clock.advance(Duration.ofMinutes(16));

        assertThat(sweeper.sweepOnce()).isEqualTo(1);
        assertThat(sweeper.sweepOnce()).isZero();
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("EXPIRED"));

        assertThat(reserved(a)).isEqualTo(1);
        assertThat(reserved(b)).isZero();
        assertThat(couponUsed("C1")).isEqualTo(1);
        api.getProductAvailable(b, 10);
    }

    @Test
    @DisplayName("R12 the sweeper only touches PENDING_PAYMENT: PAID, SHIPPED, CANCELLED and PAYMENT_FAILED orders past their TTL stay as they are")
    void sweeper_ignoresNonPendingOrders() throws Exception {
        long product = api.createProduct("Desk", 1000, 50);
        long paid = api.createPaidOrder("u", "paid", null, product, 1);
        long shipped = api.createPaidOrder("u", "shipped", null, product, 1);
        api.action(shipped, "ship").andExpect(status().isOk());
        long cancelled = api.createOrder("u", "cancelled", null, product, 1).get("id").asLong();
        api.action(cancelled, "cancel").andExpect(status().isOk());
        long failed = api.createOrder("u", "failed", null, product, 1).get("id").asLong();
        GATEWAY.chargeBehavior(com.example.order.support.PaymentGatewayStub.Behavior.DECLINE);
        api.pay(failed, "pay-failed", "tok").andExpect(status().isOk());
        int reservedBefore = reserved(product);

        clock.advance(Duration.ofHours(2));

        assertThat(sweeper.sweepOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT string_agg(status, ',' ORDER BY id) FROM orders", String.class))
                .isEqualTo("PAID,SHIPPED,CANCELLED,PAYMENT_FAILED");
        assertThat(reserved(product)).isEqualTo(reservedBefore);
        api.getOrder(paid).andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @DisplayName("R12 a sweep run handles more orders than one batch (batch-size 100) and releases all reservations")
    void sweeper_handlesMoreThanOneBatch() throws Exception {
        long product = api.createProduct("Desk", 1000, 1000);
        for (int i = 0; i < 105; i++) {
            api.createOrder("u" + i, "k" + i, null, product, 1);
        }
        assertThat(reserved(product)).isEqualTo(105);
        clock.advance(Duration.ofMinutes(16));

        assertThat(sweeper.sweepOnce()).isEqualTo(105);

        assertThat(reserved(product)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'EXPIRED'", Integer.class)).isEqualTo(105);
    }

    @Test
    @DisplayName("R12 an expired order stays expired for pay/cancel/ship/deliver and none of them releases anything a second time")
    void expiredOrder_isTerminalAndNeverReleasedTwice() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        api.createCoupon("C1", "FIXED", 100, null, null, 3);
        long bystander = api.createOrder("other", "kb", "C1", product, 2).get("id").asLong();
        long orderId = api.createOrder("u", "k", "C1", product, 1).get("id").asLong();
        jdbc.update("UPDATE orders SET expires_at = expires_at + interval '1 day' WHERE id = ?", bystander);
        clock.advance(Duration.ofMinutes(16));

        api.pay(orderId, "pay-1", "tok").andExpect(status().isConflict());
        api.action(orderId, "cancel").andExpect(status().isConflict());
        api.action(orderId, "ship").andExpect(status().isConflict());
        api.action(orderId, "deliver").andExpect(status().isConflict());
        api.getOrder(orderId).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(sweeper.sweepOnce()).isZero();

        assertThat(reserved(product)).as("only the bystander's 2 units").isEqualTo(2);
        assertThat(couponUsed("C1")).isEqualTo(1);
        assertThat(GATEWAY.requests()).isEmpty();
    }
}
