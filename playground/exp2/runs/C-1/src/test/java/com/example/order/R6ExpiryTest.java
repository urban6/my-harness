package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * 결제 만료(R6). TTL 변형 컨텍스트(PT3S)는 이 클래스 하나에만 둔다.
 * 만료 시각 이후의 일을 확인하는 R3.5(TTL)·R5.2(만료 후 결제)·R7.4/R8.2(EXPIRED 상태) 시나리오도 여기 포함한다.
 */
@TestPropertySource(properties = "order.payment-ttl=PT3S")
@DisplayName("R6 결제 만료 (order.payment-ttl=PT3S)")
class R6ExpiryTest extends IntegrationTestBase {

    private long product(long price, int stock) {
        return createProduct("상품", price, stock).get("id").asLong();
    }

    private static Instant expiresAt(JsonNode order) {
        return OffsetDateTime.parse(order.get("expiresAt").asText()).toInstant();
    }

    private static Instant createdAt(JsonNode order) {
        return OffsetDateTime.parse(order.get("createdAt").asText()).toInstant();
    }

    /** expiresAt + 2초(R6.2) 안에 조건이 참이 되는지 폴링한다. */
    private boolean becomesTrueWithinSpec(JsonNode order, java.util.function.BooleanSupplier cond) {
        return awaitUntil(expiresAt(order).plusSeconds(2), cond);
    }

    @Test
    @DisplayName("R3.5 expiresAt = createdAt + ORDER_PAYMENT_TTL(PT3S)")
    void r3_5_expiresAtUsesConfiguredTtl() {
        long p = product(1000, 10);

        JsonNode o = orderOk("u1", null, p, 1);

        assertThat(Duration.between(createdAt(o), expiresAt(o))).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("R6.1/R6.2 expiresAt 후 2초 안에 주문 EXPIRED, 상품 reserved 복원, 쿠폰 usedCount 복원")
    void r6_expiresAndRestores() {
        long p = product(1000, 10);
        createCoupon("EXPIRE01", "FIXED", 100);
        JsonNode o = orderOk("u1", "EXPIRE01", p, 3);
        long id = o.get("id").asLong();
        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(3);
        assertThat(getCoupon("EXPIRE01").get("usedCount").asInt()).isEqualTo(1);

        boolean orderExpired = becomesTrueWithinSpec(o, () -> statusOf(id).equals("EXPIRED"));
        assertThat(orderExpired).as("주문이 expiresAt + 2s 안에 EXPIRED 로 조회되어야 한다").isTrue();
        boolean productRestored = becomesTrueWithinSpec(o, () -> getProduct(p).get("reserved").asInt() == 0);
        assertThat(productRestored).as("상품 reserved 가 expiresAt + 2s 안에 복원되어야 한다").isTrue();
        boolean couponRestored = becomesTrueWithinSpec(o,
                () -> getCoupon("EXPIRE01").get("usedCount").asInt() == 0);
        assertThat(couponRestored).as("쿠폰 usedCount 가 expiresAt + 2s 안에 복원되어야 한다").isTrue();

        JsonNode prod = getProduct(p);
        assertThat(prod.get("stock").asInt()).isEqualTo(10);
        assertThat(prod.get("available").asInt()).isEqualTo(10);
        assertThat(getOrder(id).get("paidAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("R6.1 만료 전에는 PENDING_PAYMENT 와 예약이 유지된다")
    void r6_notExpiredBeforeDeadline() {
        long p = product(1000, 10);
        JsonNode o = orderOk("u1", null, p, 2);
        long id = o.get("id").asLong();

        if (Instant.now().isBefore(expiresAt(o).minusMillis(1000))) {
            assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
            assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(2);
        }
        // 만료 전에 아직 PENDING 이던 것이 정확히 expiresAt 이전에 EXPIRED 가 되면 안 된다
        while (Instant.now().isBefore(expiresAt(o).minusMillis(300))) {
            assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
            sleep(100);
        }
    }

    @Test
    @DisplayName("R6.1 여러 주문이 동시에 만료되어도 각 상품 reserved 가 정확히 복원된다")
    void r6_manyOrdersExpire() {
        long p1 = product(1000, 10);
        long p2 = product(1000, 10);
        JsonNode last = null;
        for (int i = 0; i < 4; i++) {
            last = orderOk("u" + i, null, p1, 1, p2, 2);
        }
        assertThat(getProduct(p1).get("reserved").asInt()).isEqualTo(4);
        assertThat(getProduct(p2).get("reserved").asInt()).isEqualTo(8);

        boolean ok = becomesTrueWithinSpec(last, () -> getProduct(p1).get("reserved").asInt() == 0
                && getProduct(p2).get("reserved").asInt() == 0);

        assertThat(ok).isTrue();
        assertThat(getProduct(p1).get("stock").asInt()).isEqualTo(10);
        assertThat(getProduct(p2).get("stock").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R6.1/R2.6 만료로 복원된 쿠폰은 같은 사용자가 다시 쓸 수 있고, 재고도 다시 예약할 수 있다")
    void r6_couponAndStockReusableAfterExpiry() {
        long p = product(1000, 2);
        createCoupon("EXPIRE02", "FIXED", 100);
        JsonNode o = orderOk("u1", "EXPIRE02", p, 2);
        assertProblem(order("u2", null, p, 1), 409, "INSUFFICIENT_STOCK");
        assertProblem(order("u1", "EXPIRE02", p, 1), 409, "INSUFFICIENT_STOCK");

        assertThat(becomesTrueWithinSpec(o, () -> statusOf(o.get("id").asLong()).equals("EXPIRED"))).isTrue();

        ResponseEntity<JsonNode> again = order("u1", "EXPIRE02", p, 2);
        assertThat(again.getStatusCode().value()).as("응답: %s", again.getBody()).isEqualTo(201);
        assertThat(getCoupon("EXPIRE02").get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R6.1 결제 완료(PAID)된 주문은 만료 시각이 지나도 EXPIRED 가 되지 않고 재고도 그대로")
    void r6_paidOrderIsNotExpired() {
        long p = product(1000, 10);
        JsonNode o = orderOk("u1", null, p, 2);
        payOk(o.get("id").asLong());

        sleep(Math.max(0, Duration.between(Instant.now(), expiresAt(o).plusMillis(2500)).toMillis()));

        assertThat(statusOf(o.get("id").asLong())).isEqualTo("PAID");
        JsonNode prod = getProduct(p);
        assertThat(prod.get("stock").asInt()).isEqualTo(8);
        assertThat(prod.get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R6.1 취소된 주문은 만료 시각이 지나도 예약이 두 번 복원되지 않는다")
    void r6_cancelledOrderNotRestoredAgain() {
        long p = product(1000, 10);
        JsonNode cancelled = orderOk("u1", null, p, 2);
        cancel(cancelled.get("id").asLong());
        JsonNode live = orderOk("u2", null, p, 5);
        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(5);

        sleep(Math.max(0, Duration.between(Instant.now(), expiresAt(cancelled).plusMillis(1500)).toMillis()));

        // live 주문도 같은 TTL 이라 곧 만료된다. cancelled 의 만료 처리가 reserved 를 음수 쪽으로 깎지 않았는지는
        // live 가 만료되어 0 이 되는 시점까지 reserved 가 0 미만(=CHECK 위반/응답 오류)이 되지 않음으로 본다.
        assertThat(becomesTrueWithinSpec(live, () -> getProduct(p).get("reserved").asInt() == 0)).isTrue();
        assertThat(statusOf(cancelled.get("id").asLong())).isEqualTo("CANCELLED");
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R5.2 expiresAt 이 지난 주문 결제 -> 409 INVALID_STATE, PG 미호출, 주문은 EXPIRED")
    void r5_2_payAfterExpiry() {
        long p = product(1000, 10);
        JsonNode o = orderOk("u1", null, p, 1);
        long id = o.get("id").asLong();
        sleepUntil(expiresAt(o).plusMillis(50));

        ResponseEntity<JsonNode> res = payOrder(id, key(), "tok");

        assertProblem(res, 409, "INVALID_STATE");
        assertThat(PG.payCallCount()).isZero();
        assertThat(becomesTrueWithinSpec(o, () -> statusOf(id).equals("EXPIRED"))).isTrue();
        assertThat(becomesTrueWithinSpec(o, () -> getProduct(p).get("reserved").asInt() == 0)).isTrue();
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R7.4 EXPIRED 주문 취소 -> 409 INVALID_STATE, 다른 주문의 예약은 영향 없음")
    void r7_4_cancelExpired() {
        long p = product(1000, 10);
        JsonNode expired = orderOk("u1", null, p, 2);
        long id = expired.get("id").asLong();
        assertThat(becomesTrueWithinSpec(expired, () -> statusOf(id).equals("EXPIRED"))).isTrue();
        assertThat(becomesTrueWithinSpec(expired, () -> getProduct(p).get("reserved").asInt() == 0)).isTrue();
        JsonNode live = orderOk("u2", null, p, 3);

        ResponseEntity<JsonNode> res = cancel(id);

        assertProblem(res, 409, "INVALID_STATE");
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        if (Instant.now().isBefore(expiresAt(live).minusMillis(500))) {
            assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("R8.2 EXPIRED 주문 ship/deliver -> 409 INVALID_STATE")
    void r8_2_shipExpired() {
        long p = product(1000, 10);
        JsonNode o = orderOk("u1", null, p, 1);
        long id = o.get("id").asLong();
        assertThat(becomesTrueWithinSpec(o, () -> statusOf(id).equals("EXPIRED"))).isTrue();

        assertProblem(ship(id), 409, "INVALID_STATE");
        assertProblem(deliver(id), 409, "INVALID_STATE");
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepUntil(Instant instant) {
        sleep(Duration.between(Instant.now(), instant).toMillis());
    }
}
