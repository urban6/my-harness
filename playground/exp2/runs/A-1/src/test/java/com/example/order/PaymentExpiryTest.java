package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** ORDER_PAYMENT_TTL을 짧게(PT2S) 설정한 환경. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ORDER_PAYMENT_TTL=PT2S")
@DisplayName("R6. 결제 만료")
class PaymentExpiryTest extends IntegrationTestSupport {

    @Test
    @DisplayName("C4/R3.5 ORDER_PAYMENT_TTL로 expiresAt = createdAt + TTL")
    void ttlFromEnvironment() {
        long p = createProduct(1_000, 10);
        JsonNode order = placeOrder(newUser(), null, item(p, 1));
        assertThat(Duration.between(instant(order, "createdAt"), instant(order, "expiresAt"))).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("R6.1/R6.2 expiresAt 후 2초 안에 EXPIRED가 되고 예약·쿠폰 사용이 복원되어 조회에 반영된다")
    void expires() {
        long p = createProduct(1_000, 10);
        String code = createCoupon();
        String user = newUser();
        JsonNode created = placeOrder(user, code, item(p, 3));
        long orderId = created.get("id").asLong();
        Instant expiresAt = instant(created, "expiresAt");

        assertThat(order(orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(product(p).get("reserved").asInt()).isEqualTo(3);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);

        sleepUntil(expiresAt.plusSeconds(2));

        assertThat(order(orderId).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        // 같은 사용자가 쿠폰을 다시 쓸 수 있다
        assertThat(createOrder(user, newKey(), orderBody(code, item(p, 1))).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("R5.2 expiresAt이 지난 주문의 결제는 409 INVALID_STATE이고 PG를 호출하지 않는다")
    void payAfterExpiry() {
        long p = createProduct(1_000, 10);
        JsonNode created = placeOrder(newUser(), null, item(p, 1));
        long orderId = created.get("id").asLong();

        sleepUntil(instant(created, "expiresAt").plusMillis(50));

        assertProblem(pay(orderId, newKey(), "card-ok"), 409, "INVALID_STATE");
        assertThat(PG.paymentCallsFor(orderId)).isEmpty();
    }

    @Test
    @DisplayName("R6.1 만료 전에 결제된 주문은 만료되지 않는다")
    void paidOrderDoesNotExpire() {
        long p = createProduct(1_000, 10);
        JsonNode created = paidOrder(newUser(), null, item(p, 1));
        sleepUntil(instant(created, "expiresAt").plusSeconds(2));
        assertThat(order(created.get("id").asLong()).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asInt()).isEqualTo(9);
    }
}
