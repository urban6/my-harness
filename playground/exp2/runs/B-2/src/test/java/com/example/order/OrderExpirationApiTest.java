package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import org.springframework.test.context.TestPropertySource;

/** R6. 결제 만료 — ORDER_PAYMENT_TTL 환경 변수로 만료 시간을 2초로 줄여 검증한다(C4). */
@TestPropertySource(properties = "ORDER_PAYMENT_TTL=PT2S")
class OrderExpirationApiTest extends IntegrationTestSupport {

    @Test
    void expiresAtFollowsConfiguredTtl() {
        JsonNode order = createOrder(uniqueUser(), null, item(createProduct(1_000, 10), 1)).body();

        assertThat(Duration.between(
                OffsetDateTime.parse(order.get("createdAt").asText()),
                OffsetDateTime.parse(order.get("expiresAt").asText()))).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void pendingOrderExpires_withinTwoSecondsAfterExpiresAt_andRestoresReservationAndCoupon() throws Exception {
        long p = createProduct(1_000, 10);
        String code = createCoupon(Map.of("totalQuantity", 1));
        String user = uniqueUser();
        JsonNode created = createOrder(user, code, item(p, 4)).body();
        long orderId = created.get("id").asLong();
        assertThat(product(p).get("reserved").asInt()).isEqualTo(4);

        sleepUntil(OffsetDateTime.parse(created.get("expiresAt").asText()).toInstant().plusSeconds(2));

        assertThat(order(orderId).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(product(p).get("available").asInt()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        assertThat(createOrder(user, code, item(p, 1)).status()).isEqualTo(201);
    }

    @Test
    void payAndCancelAfterExpiresAt_return409InvalidState() throws Exception {
        long p = createProduct(1_000, 10);
        JsonNode first = createOrder(uniqueUser(), null, item(p, 1)).body();
        JsonNode second = createOrder(uniqueUser(), null, item(p, 1)).body();

        sleepUntil(OffsetDateTime.parse(second.get("expiresAt").asText()).toInstant().plusMillis(50));

        assertProblem(pay(first.get("id").asLong(), uniqueKey()), 409, "INVALID_STATE");
        assertProblem(post("/api/orders/" + second.get("id").asLong() + "/cancel"), 409, "INVALID_STATE");
        assertThat(PG.payments()).isEmpty();
    }

    @Test
    void paidOrderDoesNotExpire() throws Exception {
        long p = createProduct(1_000, 10);
        JsonNode created = createOrder(uniqueUser(), null, item(p, 1)).body();
        long orderId = created.get("id").asLong();
        assertThat(pay(orderId, uniqueKey()).status()).isEqualTo(200);

        sleepUntil(OffsetDateTime.parse(created.get("expiresAt").asText()).toInstant().plusSeconds(2));

        assertThat(order(orderId).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asInt()).isEqualTo(9);
    }

    private static void sleepUntil(Instant deadline) throws InterruptedException {
        long millis = Duration.between(Instant.now(), deadline).toMillis();
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}
