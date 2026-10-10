package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * R12: proves the background @Scheduled path itself (all other tests call the sweeper by hand).
 * Own application context with the scheduler switched on and a short interval; discarded afterwards so the
 * advanced test clock of this context cannot expire other tests' orders.
 */
@TestPropertySource(properties = {
        "order-payment.expiry.sweep-enabled=true",
        "order-payment.expiry.sweep-interval=PT0.2S"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderExpirySchedulerTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R12 the scheduled sweeper expires an overdue order by itself and returns stock and coupon, without any request touching it")
    void scheduler_expiresOverdueOrderInBackground() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        api.createCoupon("C1", "FIXED", 100, null, null, 2);
        long orderId = api.createOrder("u", "k1", "C1", product, 2).get("id").asLong();
        assertThat(reserved(product)).isEqualTo(2);

        clock.advance(Duration.ofMinutes(16));

        long deadline = System.currentTimeMillis() + 10_000;
        String status = "PENDING_PAYMENT";
        while (System.currentTimeMillis() < deadline && !"EXPIRED".equals(status)) {
            Thread.sleep(100);
            status = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
        }
        assertThat(status).isEqualTo("EXPIRED");
        assertThat(reserved(product)).isZero();
        assertThat(couponUsed("C1")).isZero();
    }
}
