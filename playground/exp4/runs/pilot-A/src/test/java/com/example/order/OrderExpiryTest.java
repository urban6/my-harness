package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** R6(결제 만료)과 C4(환경 변수 ORDER_PAYMENT_TTL). TTL을 3초로 줄인 별도 컨텍스트에서 실행한다. */
@TestPropertySource(properties = "ORDER_PAYMENT_TTL=PT3S")
class OrderExpiryTest extends IntegrationTestBase {

    private static Instant instant(JsonNode node) {
        return OffsetDateTime.parse(node.asText()).toInstant();
    }

    private static void awaitUntil(Instant deadline, BooleanSupplier condition) {
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) {
            sleep(50);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("C4 ORDER_PAYMENT_TTL(PT3S)이 expiresAt에 반영된다")
    void ttlFromEnvironment() {
        JsonNode o = order(product(1000, 5), 1);
        assertThat(Duration.between(instant(o.get("createdAt")), instant(o.get("expiresAt"))))
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("R6.1·R6.2 expiresAt 후 2초 안에 주문 EXPIRED, 상품 reserved·쿠폰 usedCount 복원")
    void expiresAndRestores() {
        long p = product(1000, 10);
        String coupon = coupon("FIXED", 100, 0, null, 1);
        JsonNode created = createOrder("expire-user", "k-" + uniq(), items(p, 4), coupon).json();
        long id = created.get("id").asLong();
        Instant expiresAt = instant(created.get("expiresAt"));
        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(4);
        assertThat(couponOf(coupon).get("usedCount").asLong()).isEqualTo(1);
        assertThat(orderOf(id).get("status").asText()).isEqualTo("PENDING_PAYMENT");

        Instant deadline = expiresAt.plusSeconds(2);
        awaitUntil(deadline, () -> orderOf(id).get("status").asText().equals("EXPIRED")
                && productOf(p).get("reserved").asLong() == 0
                && couponOf(coupon).get("usedCount").asLong() == 0);

        assertThat(Instant.now()).isBefore(deadline.plusMillis(500)); // 폴링 지연 여유
        assertThat(orderOf(id).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(productOf(p).get("reserved").asLong()).isZero();
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(10);
        assertThat(productOf(p).get("available").asLong()).isEqualTo(10);
        assertThat(couponOf(coupon).get("usedCount").asLong()).isZero();
        // 사용이 복원되었으므로 같은 사용자가 같은 쿠폰을 다시 쓸 수 있다.
        assertThat(createOrder("expire-user", "k-" + uniq(), items(p, 1), coupon).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R5.2 기한이 지난 주문은 결제할 수 없다(409 INVALID_STATE), PG도 호출하지 않는다")
    void cannotPayAfterExpiry() {
        JsonNode created = order(product(1000, 10), 1);
        Instant expiresAt = instant(created.get("expiresAt"));
        while (Instant.now().isBefore(expiresAt.plusMillis(50))) {
            sleep(50);
        }

        Res r = pay(created.get("id").asLong());

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(GATEWAY.charges()).isEmpty();
    }

    @Test
    @DisplayName("R6.1 EXPIRED 주문은 취소할 수 없다(409)")
    void cannotCancelExpired() {
        JsonNode created = order(product(1000, 10), 1);
        long id = created.get("id").asLong();
        awaitUntil(instant(created.get("expiresAt")).plusSeconds(2),
                () -> orderOf(id).get("status").asText().equals("EXPIRED"));

        Res r = post("/api/orders/" + id + "/cancel", null);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
    }

    @Test
    @DisplayName("R6.1 결제가 끝난 주문과 이미 취소된 주문은 만료되지 않는다")
    void onlyPendingOrdersExpire() {
        long p = product(1000, 10);
        JsonNode paid = order(p, 2);
        JsonNode cancelled = order(p, 1);
        JsonNode pending = order(p, 1);
        long paidId = paid.get("id").asLong();
        long cancelledId = cancelled.get("id").asLong();
        assertThat(pay(paidId).status()).isEqualTo(200);
        assertThat(post("/api/orders/" + cancelledId + "/cancel", null).status()).isEqualTo(200);
        long pendingId = pending.get("id").asLong();

        awaitUntil(instant(pending.get("expiresAt")).plusSeconds(2),
                () -> orderOf(pendingId).get("status").asText().equals("EXPIRED"));
        sleep(600);

        assertThat(orderOf(pendingId).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(orderOf(paidId).get("status").asText()).isEqualTo("PAID");
        assertThat(orderOf(cancelledId).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(8);
        assertThat(productOf(p).get("reserved").asLong()).isZero();
    }
}
