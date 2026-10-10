package com.example.order;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** C4: 결제 대기 시간은 환경 변수 ORDER_PAYMENT_TTL로 덮어쓴다. */
@DisplayName("R6 결제 만료")
@TestPropertySource(properties = "ORDER_PAYMENT_TTL=PT2S")
class R06OrderExpiryTest extends IntegrationTest {

    @Test
    @DisplayName("C4·R3.5 expiresAt = createdAt + ORDER_PAYMENT_TTL")
    void ttlFromEnvironment() {
        long p = createProduct(1000, 10);
        JsonNode order = placeOrder("u1", null, p, 1).json();
        assertThat(Duration.between(OffsetDateTime.parse(order.path("createdAt").asText()),
                OffsetDateTime.parse(order.path("expiresAt").asText()))).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("R6.1·R6.2 expiresAt 후 2초 안에 EXPIRED가 되고 예약·쿠폰 사용이 복원된다")
    void expiresAndRestores() throws InterruptedException {
        long p = createProduct(1000, 10);
        createCoupon(couponBody("EXPIRE", "FIXED", 100));
        JsonNode created = placeOrder("u1", "EXPIRE", p, 4).json();
        long orderId = created.path("id").asLong();
        assertThat(product(p).path("reserved").asInt()).isEqualTo(4);
        assertThat(coupon("EXPIRE").path("usedCount").asInt()).isEqualTo(1);

        sleepUntil(OffsetDateTime.parse(created.path("expiresAt").asText()).toInstant().plusSeconds(2));

        assertThat(order(orderId).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(p).path("reserved").asInt()).isZero();
        assertThat(product(p).path("available").asInt()).isEqualTo(10);
        assertThat(coupon("EXPIRE").path("usedCount").asInt()).isZero();
        assertThat(placeOrder("u1", "EXPIRE", p, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R5.2 expiresAt이 지난 주문의 결제·취소는 409")
    void cannotPayAfterExpiry() throws InterruptedException {
        long p = createProduct(1000, 10);
        JsonNode created = placeOrder("u1", null, p, 1).json();
        long orderId = created.path("id").asLong();

        sleepUntil(OffsetDateTime.parse(created.path("expiresAt").asText()).toInstant().plusMillis(50));

        assertProblem(pay(orderId), 409, "INVALID_STATE");
        assertThat(PG.paymentRequests()).isEmpty();
        sleepUntil(Instant.now().plusSeconds(2));
        assertProblem(api.post("/api/orders/" + orderId + "/cancel", null), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R6.1 결제된 주문은 만료되지 않는다")
    void paidOrderDoesNotExpire() throws InterruptedException {
        long p = createProduct(1000, 10);
        long orderId = paidOrder("u1", null, p, 1);

        sleepUntil(Instant.now().plusSeconds(4));

        assertThat(order(orderId).path("status").asText()).isEqualTo("PAID");
        assertThat(product(p).path("stock").asInt()).isEqualTo(9);
    }

    private static void sleepUntil(Instant deadline) throws InterruptedException {
        long millis = Duration.between(Instant.now(), deadline).toMillis();
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}
