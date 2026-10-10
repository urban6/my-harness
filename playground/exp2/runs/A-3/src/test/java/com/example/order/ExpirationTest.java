package com.example.order;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R6. 결제 만료 (ORDER_PAYMENT_TTL=PT2S)")
@TestPropertySource(properties = "order.payment.ttl=PT2S")
class ExpirationTest extends IntegrationTest {

    @Test
    @DisplayName("C4. expiresAt = createdAt + ORDER_PAYMENT_TTL")
    void ttlIsConfigurable() {
        JsonNode order = placeOrder(item(createProduct(1_000, 10), 1));
        assertThat(Duration.between(instant(order.path("createdAt")), instant(order.path("expiresAt"))))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("R6.1·R6.2 expiresAt 후 2초 안에 EXPIRED 가 되고 예약·쿠폰 사용이 복원되어 조회에 반영된다")
    void expiresAndRestores() {
        long productId = createProduct(1_000, 10);
        String coupon = createCoupon("FIXED", 100);
        String user = uniqueUser();
        JsonNode created = placeOrder(user, coupon, item(productId, 4));
        long orderId = created.path("id").asLong();
        assertThat(product(productId).path("reserved").asInt()).isEqualTo(4);
        assertThat(coupon(coupon).path("usedCount").asInt()).isEqualTo(1);

        sleepUntil(instant(created.path("expiresAt")).plusSeconds(2));

        assertThat(order(orderId).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(productId).path("reserved").asInt()).isZero();
        assertThat(product(productId).path("available").asInt()).isEqualTo(10);
        assertThat(coupon(coupon).path("usedCount").asInt()).isZero();
        // 사용이 복원되었으므로 같은 사용자가 다시 쓸 수 있다
        assertThat(createOrder(user, coupon, List.of(item(productId, 1))).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R5.2 expiresAt 이 지난 주문의 결제는 409 INVALID_STATE, PG 호출 없음")
    void payAfterExpiry() {
        long productId = createProduct(1_000, 10);
        JsonNode created = placeOrder(item(productId, 1));
        long orderId = created.path("id").asLong();

        sleepUntil(instant(created.path("expiresAt")).plusMillis(100));

        assertProblem(pay(orderId, "tok_ok"), 409, "INVALID_STATE");
        assertThat(pg.paymentCallsFor(orderId)).isEmpty();
        assertThat(order(orderId).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(product(productId).path("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R7.4 만료된 주문의 취소는 409 INVALID_STATE")
    void cancelAfterExpiry() {
        JsonNode created = placeOrder(item(createProduct(1_000, 10), 1));
        sleepUntil(instant(created.path("expiresAt")).plusMillis(100));

        assertProblem(post("/api/orders/" + created.path("id").asLong() + "/cancel", null), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R6.1 결제된 주문은 expiresAt 이 지나도 만료되지 않는다")
    void paidOrderDoesNotExpire() {
        long productId = createProduct(1_000, 10);
        JsonNode created = placeOrder(item(productId, 2));
        long orderId = created.path("id").asLong();
        assertThat(pay(orderId, "tok_ok").status()).isEqualTo(200);

        sleepUntil(instant(created.path("expiresAt")).plusSeconds(2));

        assertThat(order(orderId).path("status").asText()).isEqualTo("PAID");
        assertThat(product(productId).path("stock").asInt()).isEqualTo(8);
        assertThat(product(productId).path("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R6.2 여러 주문이 함께 만료되어도 모두 정확히 복원된다")
    void manyOrdersExpire() {
        long productId = createProduct(1_000, 50);
        Instant lastExpiry = Instant.EPOCH;
        for (int i = 0; i < 10; i++) {
            lastExpiry = instant(placeOrder(item(productId, 3)).path("expiresAt"));
        }
        assertThat(product(productId).path("reserved").asInt()).isEqualTo(30);

        sleepUntil(lastExpiry.plusSeconds(2));

        assertThat(product(productId).path("reserved").asInt()).isZero();
        assertThat(product(productId).path("stock").asInt()).isEqualTo(50);
    }
}
