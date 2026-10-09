package com.example.order.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@DisplayName("R6 결제 만료 (ORDER_PAYMENT_TTL=PT2S)")
@TestPropertySource(properties = "order.payment-ttl=PT2S")
class OrderExpiryTest extends IntegrationTest {

    @Test
    @DisplayName("R6.1/R6.2 expiresAt 후 2초 안에 EXPIRED 가 되고 예약·쿠폰 사용이 복원된다")
    void expiresAndReleases() throws InterruptedException {
        long productId = createProduct(1_000, 10);
        String code = createCoupon(Map.of());
        ApiResponse created = placeOrder(uniqueUser(), code, List.of(item(productId, 4)));
        JsonNode o = created.body();
        OffsetDateTime createdAt = OffsetDateTime.parse(o.get("createdAt").asText());
        OffsetDateTime expiresAt = OffsetDateTime.parse(o.get("expiresAt").asText());
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofSeconds(2)); // C4: TTL 설정 반영
        assertProduct(productId, 10, 4);

        sleepUntil(expiresAt.plusSeconds(2));

        assertThat(orderStatus(created.id())).isEqualTo("EXPIRED");
        assertProduct(productId, 10, 0);
        assertThat(usedCount(code)).isZero();
    }

    @Test
    @DisplayName("R6.1 만료 전에는 PENDING_PAYMENT 를 유지한다")
    void notExpiredBeforeDeadline() {
        long productId = createProduct(1_000, 10);
        long orderId = createOrder(uniqueUser(), null, List.of(item(productId, 1)));

        assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
        assertProduct(productId, 10, 1);
    }

    @Test
    @DisplayName("R5.2 expiresAt 이 지난 주문의 결제는 409 INVALID_STATE, PG 호출 없음")
    void payAfterExpiry() throws InterruptedException {
        long orderId = createOrder(uniqueUser(), null, List.of(item(createProduct(1_000, 10), 1)));
        sleepUntil(OffsetDateTime.parse(order(orderId).get("expiresAt").asText()).plusNanos(1_000_000));

        assertProblem(pay(orderId), 409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).isEmpty();
    }

    @Test
    @DisplayName("R6 결제 완료된 주문은 만료되지 않는다")
    void paidOrderDoesNotExpire() throws InterruptedException {
        long productId = createProduct(1_000, 10);
        long orderId = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        payOk(orderId);

        sleepUntil(OffsetDateTime.parse(order(orderId).get("expiresAt").asText()).plusSeconds(2));

        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertProduct(productId, 9, 0);
    }

    private static void sleepUntil(OffsetDateTime deadline) throws InterruptedException {
        long millis = Duration.between(OffsetDateTime.now(), deadline).toMillis();
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}
