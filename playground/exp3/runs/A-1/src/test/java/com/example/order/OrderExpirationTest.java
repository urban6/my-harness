package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "order.payment-ttl=PT1S")
class OrderExpirationTest extends IntegrationTestBase {

    @Test
    void unpaidOrderExpiresAndReleasesStockAndCoupon() throws Exception {
        long productId = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 0, null, 5);
        long id = read(placeOrder("u1", null, orderBody(code, productId, 3))).get("id").asLong();
        assertThat(product(productId).get("reserved").asLong()).isEqualTo(3);

        // 주문을 직접 조회하지 않아도 스케줄러가 예약을 되돌린다.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(product(productId).get("reserved").asLong()).isZero();
            assertThat(coupon(code).get("usedCount").asLong()).isZero();
        });

        assertThat(getOrder(id).get("status").asText()).isEqualTo("EXPIRED");
        pay(id, "k", "tok_ok").andExpect(status().isConflict());
        action(id, "cancel").andExpect(status().isConflict());
        assertThat(PG.chargeKeys).isEmpty();
        assertThat(product(productId).get("reserved").asLong()).isZero(); // 이중 해제 없음
    }

    @Test
    void payingAfterTheDeadlineIsRejectedEvenBeforeTheSweeperRuns() throws Exception {
        long productId = createProduct(1000, 10);
        long id = order("u1", productId, 1);
        Thread.sleep(1200);

        pay(id, "k", "tok_ok").andExpect(status().isConflict());

        assertThat(PG.chargeKeys).isEmpty();
        assertThat(getOrder(id).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(productId).get("reserved").asLong()).isZero();
    }

    @Test
    void paidOrdersDoNotExpire() throws Exception {
        long productId = createProduct(1000, 10);
        long id = order("u1", productId, 1);
        pay(id, "k", "tok_ok").andExpect(status().isOk());
        Thread.sleep(2500);

        assertThat(getOrder(id).get("status").asText()).isEqualTo("PAID");
        assertThat(product(productId).get("stock").asLong()).isEqualTo(9);
    }
}
