package com.example.order;

import static com.example.order.support.TestApi.item;
import static com.example.order.support.TestApi.newUserId;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@DisplayName("R6 결제 만료")
@TestPropertySource(properties = "order.payment-ttl=PT2S")
class OrderExpirationApiTest extends IntegrationTest {

    @Test
    @DisplayName("R6.1·R6.2 expiresAt 후 2초 안에 EXPIRED, 예약·쿠폰 사용 복원")
    void pendingOrder_expiresWithinTwoSeconds() throws Exception {
        long productId = api.createProduct(1_000, 10);
        String code = api.createCoupon("FIXED", 100);
        String user = newUserId();
        JsonNode order = api.createOrder(user, code, List.of(item(productId, 3))).assertStatus(201).body();
        long orderId = order.get("id").asLong();
        Instant createdAt = Instant.parse(order.get("createdAt").asText());
        Instant expiresAt = Instant.parse(order.get("expiresAt").asText());
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofSeconds(2));
        assertThat(api.order(orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");

        sleepUntil(expiresAt.plusSeconds(2));

        assertThat(api.order(orderId).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(api.product(productId).get("reserved").asInt()).isZero();
        assertThat(api.product(productId).get("available").asInt()).isEqualTo(10);
        assertThat(api.coupon(code).get("usedCount").asInt()).isZero();
        api.createOrder(user, code, List.of(item(productId, 1))).assertStatus(201);
    }

    @Test
    @DisplayName("R5.2 expiresAt이 지난 주문 결제 → 409 INVALID_STATE, PG 호출 없음")
    void payAfterExpiry_returns409() throws Exception {
        long productId = api.createProduct(1_000, 10);
        JsonNode order = api.createOrder(newUserId(), null, List.of(item(productId, 1))).assertStatus(201).body();

        sleepUntil(Instant.parse(order.get("expiresAt").asText()).plusMillis(100));

        api.pay(order.get("id").asLong()).assertProblem(409, "INVALID_STATE");
        api.cancel(order.get("id").asLong()).assertProblem(409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).isEmpty();
    }

    @Test
    @DisplayName("R6.1 결제 완료 주문은 만료되지 않음")
    void paidOrder_doesNotExpire() throws Exception {
        long productId = api.createProduct(1_000, 10);
        JsonNode order = api.createOrder(newUserId(), null, List.of(item(productId, 1))).assertStatus(201).body();
        api.pay(order.get("id").asLong()).assertStatus(200);

        sleepUntil(Instant.parse(order.get("expiresAt").asText()).plusSeconds(2));

        assertThat(api.order(order.get("id").asLong()).get("status").asText()).isEqualTo("PAID");
        assertThat(api.product(productId).get("stock").asInt()).isEqualTo(9);
    }

    private static void sleepUntil(Instant deadline) throws InterruptedException {
        long millis = Duration.between(Instant.now(), deadline).toMillis();
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}
