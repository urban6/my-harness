package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** C4: 결제 대기 만료 시간을 ORDER_PAYMENT_TTL로 덮어쓴다. */
@DisplayName("R6 결제 만료")
@TestPropertySource(properties = "ORDER_PAYMENT_TTL=PT2S")
class OrderExpirationApiTest extends IntegrationTest {

    @Test
    @DisplayName("C4/R3.5 expiresAt = createdAt + ORDER_PAYMENT_TTL")
    void ttlFromEnvironment() {
        JsonNode order = createOrder(uniqueUser(), null, createProduct(1000, 10), 1);
        assertThat(Duration.between(OffsetDateTime.parse(order.get("createdAt").asText()),
                OffsetDateTime.parse(order.get("expiresAt").asText()))).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("R6.1/R6.2 expiresAt 후 2초 안에 EXPIRED, 예약·쿠폰 사용 복원이 조회에 반영된다")
    void expires() throws InterruptedException {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 1);
        String user = uniqueUser();
        JsonNode created = createOrder(user, code, p, 3);
        long orderId = created.get("id").asLong();
        assertThat(product(p).get("reserved").asLong()).isEqualTo(3);
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);

        sleepUntil(OffsetDateTime.parse(created.get("expiresAt").asText()).plusSeconds(2));

        assertThat(order(orderId).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(p).get("reserved").asLong()).isZero();
        assertThat(product(p).get("stock").asLong()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asLong()).isZero();
        createOrder(user, code, p, 1); // 같은 사용자가 쿠폰을 다시 쓸 수 있다
    }

    @Test
    @DisplayName("R5.2/R6 expiresAt이 지난 주문의 결제·취소는 409 INVALID_STATE, PG는 호출하지 않는다")
    void payAfterExpiry() throws InterruptedException {
        long p = createProduct(1000, 10);
        JsonNode created = createOrder(uniqueUser(), null, p, 1);
        long orderId = created.get("id").asLong();

        sleepUntil(OffsetDateTime.parse(created.get("expiresAt").asText()).plus(Duration.ofMillis(100)));

        assertProblem(pay(orderId, uniqueKey(), "tok"), 409, "INVALID_STATE");
        assertProblem(api.post("/api/orders/" + orderId + "/cancel", null), 409, "INVALID_STATE");
        assertThat(PG.paymentCallsFor(orderId)).isEmpty();
        assertThat(order(orderId).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(p).get("reserved").asLong()).isZero();
    }

    @Test
    @DisplayName("R6.1 기한 안에 결제된 주문은 만료되지 않는다")
    void paidOrderDoesNotExpire() throws InterruptedException {
        long p = createProduct(1000, 10);
        JsonNode paid = paidOrder(uniqueUser(), null, "tok", p, 2);

        sleepUntil(OffsetDateTime.parse(paid.get("expiresAt").asText()).plusSeconds(2));

        assertThat(order(paid.get("id").asLong()).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asLong()).isEqualTo(8);
        assertThat(product(p).get("reserved").asLong()).isZero();
    }

    private static void sleepUntil(OffsetDateTime time) throws InterruptedException {
        long millis = Duration.between(OffsetDateTime.now(), time).toMillis();
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}
