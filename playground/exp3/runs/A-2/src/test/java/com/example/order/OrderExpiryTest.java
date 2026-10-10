package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "order.payment-ttl=PT1S")
class OrderExpiryTest extends ApiTestSupport {

    @Test
    void unpaidOrderExpiresAndReleasesStockAndCoupon() throws Exception {
        long p = createProduct(1000, 5);
        String code = createCoupon("FIXED", 100, 0, null, 1);
        JsonNode order = newOrder("u-" + uid(), code, p, 2);
        long id = order.get("id").asLong();

        Duration ttl = Duration.between(Instant.parse(order.get("createdAt").asText()),
                Instant.parse(order.get("expiresAt").asText()));
        assertThat(ttl).isEqualTo(Duration.ofSeconds(1));
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);

        // 주문을 조회하지 않아도 스케줄러가 만료시켜 재고·쿠폰을 돌려준다
        long deadline = System.currentTimeMillis() + 10_000;
        while (product(p).get("reserved").asInt() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        assertThat(order(id).get("status").asText()).isEqualTo("EXPIRED");

        pay(id, "pay-" + uid(), "tok_ok").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));
        assertThat(PG.charges(id)).isEmpty();
        action(id, "cancel").andExpect(status().isConflict());
    }

    @Test
    void payingJustAfterDeadlineExpiresInsteadOfCharging() throws Exception {
        long p = createProduct(1000, 5);
        long id = newOrder("u-" + uid(), null, p, 1).get("id").asLong();
        Thread.sleep(1100);

        pay(id, "pay-" + uid(), "tok_ok").andExpect(status().isConflict());
        assertThat(PG.charges(id)).isEmpty();
        assertThat(order(id).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(p).get("reserved").asInt()).isZero();
    }

    @Test
    void paidOrderDoesNotExpire() throws Exception {
        long p = createProduct(1000, 5);
        long id = newOrder("u-" + uid(), null, p, 1).get("id").asLong();
        pay(id, "pay-" + uid(), "tok_ok").andExpect(status().isOk());
        Thread.sleep(1500);
        assertThat(order(id).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asInt()).isEqualTo(4);
    }
}
