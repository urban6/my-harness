package com.example.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiClient;
import com.example.order.support.Containers;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * REQ-13 end-to-end with the real @Scheduled sweeper (TTL 3s, interval 1s). Status is observed via JDBC only, because
 * a GET would trigger the lazy expiry path and hide a broken scheduler. The context is discarded afterwards so the
 * sweeper does not keep running during other test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "order.expiry-sweep-enabled=true", "order.expiry-sweep-interval=PT1S", "order.payment-ttl=PT3S",
        "payment.gateway.url=http://127.0.0.1:1" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("REQ-13 scheduled expiry sweep")
class OrderExpirySchedulerTest {

    static {
        Containers.postgres();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.register(registry, Containers.postgres());
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    private String status(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    @Test
    @DisplayName("REQ-13 스케줄러가 TTL 경과 주문을 (조회 없이) EXPIRED 로 전환하고 재고/쿠폰을 해제하며, PAID 주문은 건드리지 않는다")
    void schedulerExpiresDueOrdersAndSkipsPaid() throws Exception {
        ApiClient api = new ApiClient(rest);
        long p = api.newProduct(1000, 10);
        String coupon = api.newCoupon("FIXED", 100, null, null, 5);
        JsonNode pending = api.orderOk(ApiClient.uniq("u"), p, 3, coupon);
        long pendingId = pending.get("id").asLong();
        long paidId = api.orderOk(ApiClient.uniq("u"), p, 1, null).get("id").asLong();
        jdbc.update("update orders set status = 'PAID', paid_at = now() where id = ?", paidId);
        assertThat(Duration.between(java.time.Instant.parse(pending.get("createdAt").asText()),
                java.time.Instant.parse(pending.get("expiresAt").asText()))).isEqualTo(Duration.ofSeconds(3));

        long deadline = System.currentTimeMillis() + 30_000;
        while (!"EXPIRED".equals(status(pendingId)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
        }

        assertThat(status(pendingId)).isEqualTo("EXPIRED");
        assertThat(status(paidId)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select reserved from products where id = ?", Integer.class, p)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select used_count from coupons where code = ?", Integer.class, coupon))
                .isZero();
    }
}
