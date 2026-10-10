package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * R6. 결제 만료. 별도 컨텍스트에서 TTL 을 PT3S 로 줄이고(환경 변수 ORDER_PAYMENT_TTL 과 같은 키 order.payment.ttl),
 * 만료 스케줄러가 기본 간격으로 동작하게 둔다. 만료 반영은 폴링하되, 마감은 expiresAt + 2초(R6.2)로 단언한다.
 * 주문 단건 조회는 지연 평가로 만료를 앞당길 수 있으므로, 스케줄러 효과를 보는 테스트는 상품·쿠폰·목록 조회로 폴링한다.
 */
@TestPropertySource(properties = "order.payment.ttl=PT3S")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderExpiryTest extends IntegrationTestBase {

    private static final Duration DEADLINE_AFTER_EXPIRY = Duration.ofSeconds(2);

    /** deadline 까지 condition 이 참이 되기를 폴링한다. 마감 안에 참이 됐으면 true. */
    private static boolean awaitUntil(Instant deadline, BooleanSupplier condition) {
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (Instant.now().isAfter(deadline)) {
                return condition.getAsBoolean();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static Instant expiryDeadline(JsonNode order) {
        return time(order, "expiresAt").toInstant().plus(DEADLINE_AFTER_EXPIRY);
    }

    private boolean reservedIs(long productId, int expected) {
        return product(productId).get("reserved").asInt() == expected;
    }

    @Test
    @DisplayName("R3.5/C4 TTL 설정(order.payment.ttl=PT3S)이 expiresAt = createdAt + 3초 로 반영된다")
    void c4_ttlSettingIsAppliedToExpiresAt() {
        JsonNode order = newOrder(newProduct(1000, 5), 1);

        assertThat(Duration.between(time(order, "createdAt"), time(order, "expiresAt"))).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("R6.1/R6.2 만료되면 상품 조회에 예약 복원이 expiresAt + 2초 안에 반영된다 (그 전에는 예약 유지)")
    void r6_productReservationRestoredWithinTwoSecondsOfExpiry() {
        long productId = newProduct(1000, 10);
        JsonNode order = newOrder(productId, 4);
        if (Instant.now().isBefore(time(order, "expiresAt").toInstant())) {
            assertThat(reservedIs(productId, 4)).as("만료 전에는 예약 유지").isTrue();
        }

        boolean restored = awaitUntil(expiryDeadline(order), () -> reservedIs(productId, 0));

        assertThat(restored).as("expiresAt + 2s 안에 reserved 가 0 으로 복원되어야 한다").isTrue();
        assertThat(Instant.now()).isAfterOrEqualTo(time(order, "expiresAt").toInstant());
        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R6.1/R6.2 만료되면 쿠폰 조회에 usedCount 복원이 expiresAt + 2초 안에 반영된다")
    void r6_couponUsageRestoredWithinTwoSecondsOfExpiry() {
        String code = newCoupon("FIXED", 100, 5);
        JsonNode order = newOrder(uniqueUser(), code, items(newProduct(1000, 10), 1));
        assertUsedCount(code, 1);

        boolean restored = awaitUntil(expiryDeadline(order), () -> coupon(code).get("usedCount").asInt() == 0);

        assertThat(restored).as("expiresAt + 2s 안에 usedCount 가 0 으로 복원되어야 한다").isTrue();
    }

    @Test
    @DisplayName("R6.1/R6.2 만료되면 주문 목록 조회에 EXPIRED 가 expiresAt + 2초 안에 반영된다 (목록은 지연 평가 없이 스케줄러에 의존)")
    void r6_orderListShowsExpiredWithinTwoSecondsOfExpiry() {
        String user = uniqueUser();
        JsonNode order = newOrder(user, null, items(newProduct(1000, 10), 1));
        long orderId = order.get("id").asLong();

        boolean expired = awaitUntil(expiryDeadline(order),
                () -> "EXPIRED".equals(get("/api/orders?userId=" + user).getBody().get("content").get(0)
                        .get("status").asText()));

        assertThat(expired).as("expiresAt + 2s 안에 목록에서 EXPIRED 여야 한다").isTrue();
        JsonNode single = order(orderId);
        assertThat(single.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(single.get("paidAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("R6.1 주문 단건 조회도 expiresAt + 2초 안에 EXPIRED 를 보여준다")
    void r6_singleOrderReadShowsExpiredWithinTwoSeconds() {
        JsonNode order = newOrder(newProduct(1000, 10), 1);
        long orderId = order.get("id").asLong();

        boolean expired = awaitUntil(expiryDeadline(order), () -> "EXPIRED".equals(statusOf(orderId)));

        assertThat(expired).isTrue();
    }

    @Test
    @DisplayName("R6.1 동시에 만료되는 여러 주문(12건)이 모두 기한 안에 복원된다")
    void r6_manyOrdersExpireTogether() {
        long productId = newProduct(1000, 100);
        String user = uniqueUser();
        List<JsonNode> orders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            orders.add(newOrder(user, null, items(productId, 2)));
        }
        Instant deadline = expiryDeadline(orders.get(orders.size() - 1));

        boolean restored = awaitUntil(deadline, () -> reservedIs(productId, 0));

        assertThat(restored).isTrue();
        assertStock(productId, 100, 0);
        orders.forEach(o -> assertThat(statusOf(o.get("id").asLong())).isEqualTo("EXPIRED"));
    }

    @Test
    @DisplayName("R6.1 PAID·CANCELLED·PAYMENT_FAILED 주문은 expiresAt 이 지나도 만료되지 않고 이중 복원도 없다")
    void r6_nonPendingOrdersAreNotExpired() throws Exception {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 10);
        JsonNode cancelled = newOrder(uniqueUser(), code, items(productId, 1));
        JsonNode paid = newOrder(uniqueUser(), code, items(productId, 2));
        JsonNode failed = newOrder(uniqueUser(), code, items(productId, 3));
        assertStatus(cancel(cancelled.get("id").asLong()), 200);
        payOk(paid.get("id").asLong());
        assertStatus(pay(failed.get("id").asLong(), uniqueKey(), "decline_card"), 402);

        // 마지막 주문의 만료 시각 + 2초가 지날 때까지 계속 상태를 확인한다.
        Instant until = expiryDeadline(failed).plusMillis(500);
        while (Instant.now().isBefore(until)) {
            assertThat(statusOf(cancelled.get("id").asLong())).isEqualTo("CANCELLED");
            assertThat(statusOf(paid.get("id").asLong())).isEqualTo("PAID");
            assertThat(statusOf(failed.get("id").asLong())).isEqualTo("PAYMENT_FAILED");
            Thread.sleep(250);
        }

        assertStock(productId, 8, 0); // paid 2개만 판매
        assertUsedCount(code, 1); // paid 만 사용 중
    }

    @Test
    @DisplayName("R6.1/R5.2 만료된 주문을 결제하면 409 INVALID_STATE, PG 호출 없음")
    void r6_payAfterExpiryReturns409() {
        long productId = newProduct(1000, 10);
        JsonNode order = newOrder(productId, 1);
        long orderId = order.get("id").asLong();
        assertThat(awaitUntil(expiryDeadline(order), () -> reservedIs(productId, 0))).isTrue();

        assertProblem(pay(orderId), 409, "INVALID_STATE");

        assertThat(PG.requests()).isEmpty();
        assertThat(statusOf(orderId)).isEqualTo("EXPIRED");
        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R6.1/R7.4 만료된 주문을 취소하면 409 INVALID_STATE 이고 예약은 한 번만 복원된다")
    void r6_cancelAfterExpiryReturns409() {
        long productId = newProduct(1000, 10);
        JsonNode order = newOrder(productId, 3);
        assertThat(awaitUntil(expiryDeadline(order), () -> reservedIs(productId, 0))).isTrue();

        assertProblem(cancel(order.get("id").asLong()), 409, "INVALID_STATE");

        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R2.6/R6.1 만료로 복원된 쿠폰은 같은 사용자가 다시 쓸 수 있다")
    void r6_couponReusableByTheSameUserAfterExpiry() {
        String code = newCoupon("FIXED", 100, 5);
        long productId = newProduct(1000, 10);
        String user = uniqueUser();
        JsonNode order = newOrder(user, code, items(productId, 1));
        assertProblem(placeOrder(user, code, productId, 1), 409, "COUPON_NOT_APPLICABLE");
        assertThat(awaitUntil(expiryDeadline(order), () -> coupon(code).get("usedCount").asInt() == 0)).isTrue();

        assertThat(code(placeOrder(user, code, productId, 1))).isEqualTo(201);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R6.1/R3.3 만료로 풀린 재고는 다른 주문이 다시 쓸 수 있다")
    void r6_expiredStockOrderableAgain() {
        long productId = newProduct(1000, 2);
        JsonNode order = newOrder(productId, 2);
        assertProblem(placeOrder(uniqueUser(), null, productId, 1), 409, "INSUFFICIENT_STOCK");
        assertThat(awaitUntil(expiryDeadline(order), () -> reservedIs(productId, 0))).isTrue();

        assertThat(code(placeOrder(uniqueUser(), null, productId, 2))).isEqualTo(201);
    }
}
