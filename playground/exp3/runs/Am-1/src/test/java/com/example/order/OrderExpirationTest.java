package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"app.order-payment-ttl=PT1S", "app.expiration-scan-interval=PT0.2S"})
class OrderExpirationTest extends AbstractIntegrationTest {

    @Test
    void unpaidOrderExpiresAndReleasesReservation() throws Exception {
        long pid = createProduct(100, 3);
        String code = createCoupon("FIXED", 10, 0, null, 1);
        long id = read(order("u1", "exp-1", code, pid, 2)).get("id").asLong();
        assertThat(product(pid).get("reserved").asInt()).isEqualTo(2);

        Thread.sleep(2000);

        mvc.perform(get("/api/orders/" + id)).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(product(pid).get("reserved").asInt()).isZero();
        mvc.perform(get("/api/coupons/" + code)).andExpect(jsonPath("$.usedCount").value(0));
        pay(id, "late").andExpect(status().isConflict());
        action(id, "cancel").andExpect(status().isConflict());
        assertThat(GATEWAY.chargeCalls.get()).isZero();
    }

    @Test
    void paidOrderDoesNotExpire() throws Exception {
        long pid = createProduct(100, 3);
        long id = newOrder(pid, 1);
        pay(id, "p1").andExpect(status().isOk());
        Thread.sleep(2000);
        mvc.perform(get("/api/orders/" + id)).andExpect(jsonPath("$.status").value("PAID"));
    }
}
