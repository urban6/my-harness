package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * 결제 TTL 을 3초로 줄인 별도 컨텍스트(order.payment-ttl=PT3S)에서 만료 스위퍼를 실제로 돌려 검증한다.
 * (다른 컨텍스트의 스위퍼도 같은 DB 에서 돌 수 있으나 SKIP LOCKED + 조건 재확인으로 이중 복원은 없어야 한다.)
 */
@TestPropertySource(properties = "order.payment-ttl=PT3S")
@DisplayName("R6. 결제 만료 (TTL=PT3S)")
class R6PaymentExpiryTest extends IntegrationTestBase {

    private static final Duration SPEC_LIMIT = Duration.ofSeconds(2);

    private Instant expiresAt(JsonNode order) {
        return Instant.parse(order.get("expiresAt").asText());
    }

    private static void sleepUntil(Instant target) {
        long millis = Duration.between(Instant.now(), target).toMillis();
        if (millis > 0) {
            sleepMillis(millis);
        }
    }

    /** status 가 expected 가 될 때까지 20ms 간격으로 폴링해 처음 관찰한 시각을 돌려준다. 못 보면 null. */
    private Instant awaitStatus(long orderId, String expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(orderStatus(orderId))) {
                return Instant.now();
            }
            sleepMillis(20);
        }
        return null;
    }

    @Test
    @DisplayName("C4/R3.5 order.payment-ttl=PT3S 가 반영되어 expiresAt = createdAt + 3초")
    void c4_ttlOverride_isReflectedInExpiresAt() {
        long productId = product(1_000, 5);

        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1)));

        Duration ttl = Duration.between(Instant.parse(o.get("createdAt").asText()), expiresAt(o));
        assertThat(ttl).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("R6.1 만료 전에는 PENDING_PAYMENT 이고 예약이 유지된다")
    void r6_1_beforeExpiry_orderStaysPending() {
        long productId = product(1_000, 5);
        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 2)));

        assertThat(Instant.now()).isBefore(expiresAt(o));
        assertThat(orderStatus(o.get("id").asLong())).isEqualTo("PENDING_PAYMENT");
        assertThat(reservedOf(productId)).isEqualTo(2);
    }

    @Test
    @DisplayName("R6.1/R6.2 expiresAt 이 지나면 2초 안에 주문이 EXPIRED 로 바뀐다")
    void r6_2_orderBecomesExpiredWithin2SecondsOfExpiresAt() {
        long productId = product(1_000, 5);
        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1)));
        long orderId = o.get("id").asLong();

        Instant observedAt = awaitStatus(orderId, "EXPIRED", Duration.ofSeconds(8));

        assertThat(observedAt).as("EXPIRED 를 관찰하지 못함").isNotNull();
        assertThat(observedAt).isAfterOrEqualTo(expiresAt(o));
        assertThat(observedAt).isBeforeOrEqualTo(expiresAt(o).plus(SPEC_LIMIT));
    }

    @Test
    @DisplayName("R6.1/R6.2 expiresAt + 2초 시점에 주문·상품·쿠폰 조회가 모두 만료를 반영한다")
    void r6_2_orderProductAndCoupon_reflectExpiryAtExpiresAtPlus2s() {
        long p1 = product(10_000, 10);
        long p2 = product(5_000, 10);
        String code = uniqueCode("EXPC");
        createCoupon(code, "FIXED", 1_000, 0, null, 3);
        JsonNode o = orderOk(uid("u"), orderBody(code, item(p1, 3), item(p2, 4)));
        long orderId = o.get("id").asLong();
        assertThat(reservedOf(p1)).isEqualTo(3);
        assertThat(usedCountOf(code)).isEqualTo(1L);

        sleepUntil(expiresAt(o).plus(SPEC_LIMIT));

        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(reservedOf(p1)).isZero();
        assertThat(reservedOf(p2)).isZero();
        assertThat(stockOf(p1)).isEqualTo(10);
        assertThat(availableOf(p1)).isEqualTo(10);
        assertThat(usedCountOf(code)).isZero();
    }

    @Test
    @DisplayName("R6.1 만료된 주문의 쿠폰은 같은 사용자가 다시 사용할 수 있다")
    void r6_1_expiredCoupon_canBeReusedBySameUser() {
        long productId = product(10_000, 10);
        String code = uniqueCode("EXPR");
        createCoupon(code, "FIXED", 1_000, 0, null, 3);
        String user = uid("u");
        JsonNode o = orderOk(user, orderBody(code, item(productId, 1)));
        assertThat(awaitStatus(o.get("id").asLong(), "EXPIRED", Duration.ofSeconds(8))).isNotNull();

        ResponseEntity<String> again = createOrder(user, uid("k"), orderBody(code, item(productId, 1)));

        assertThat(statusOf(again)).isEqualTo(201);
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R6.1 같은 시점에 만료되는 여러 주문의 예약이 정확히 한 번씩만 복원된다")
    void r6_1_manyExpiringOrders_restoreExactly() {
        long productId = product(1_000, 10);
        long last = 0;
        for (int i = 0; i < 6; i++) {
            last = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        }
        assertThat(reservedOf(productId)).isEqualTo(6);
        assertThat(awaitStatus(last, "EXPIRED", Duration.ofSeconds(8))).isNotNull();
        sleepMillis(1_000); // 나머지 주문도 스위퍼 한 바퀴 이상 지나가게 둔다

        assertThat(reservedOf(productId)).isZero();
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("R6.1 만료된 주문은 status=EXPIRED 목록 조회에 나타난다")
    void r6_1_expiredOrder_isListedByStatusFilter() {
        long productId = product(1_000, 5);
        String user = uid("u");
        long orderId = orderIdOk(user, orderBody(null, item(productId, 1)));
        assertThat(awaitStatus(orderId, "EXPIRED", Duration.ofSeconds(8))).isNotNull();

        JsonNode page = json(get("/api/orders?userId=" + user + "&status=EXPIRED"));

        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("content").get(0).get("id").asLong()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("R6.1 결제 완료(PAID) 주문은 TTL 이 지나도 만료되지 않는다")
    void r6_1_paidOrder_doesNotExpire() {
        long productId = product(1_000, 5);
        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 2)));
        long orderId = o.get("id").asLong();
        assertThat(statusOf(pay(orderId, uid("pk"), "tok"))).isEqualTo(200);

        sleepUntil(expiresAt(o).plusSeconds(1));

        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("R6.1 이미 취소된 주문은 TTL 이 지나도 예약·쿠폰이 두 번 복원되지 않는다")
    void r6_1_cancelledOrder_isNotRestoredTwice() {
        long productId = product(10_000, 10);
        String code = uniqueCode("EXPX");
        createCoupon(code, "FIXED", 1_000, 0, null, 3);
        JsonNode cancelled = orderOk(uid("u"), orderBody(code, item(productId, 2)));
        cancel(cancelled.get("id").asLong());
        // 다른 사용자의 주문이 같은 상품·쿠폰을 점유 중이고, 결제로 확정된다
        JsonNode other = orderOk(uid("u"), orderBody(code, item(productId, 3)));
        payOk(other.get("id").asLong());

        sleepUntil(expiresAt(cancelled).plusSeconds(1));

        assertThat(orderStatus(cancelled.get("id").asLong())).isEqualTo("CANCELLED");
        assertThat(reservedOf(productId)).isZero();
        assertThat(stockOf(productId)).isEqualTo(7);
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R5.2 만료된 주문을 결제하면 409 INVALID_STATE 이고 PG 는 호출되지 않는다")
    void r5_2_payAfterExpiry_returns409() {
        long productId = product(1_000, 5);
        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1)));
        long orderId = o.get("id").asLong();
        assertThat(awaitStatus(orderId, "EXPIRED", Duration.ofSeconds(8))).isNotNull();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).isZero();
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("R5.2 expiresAt 직후(스위퍼가 처리하기 전일 수 있는 시점)에 결제해도 409 INVALID_STATE")
    void r5_2_payRightAfterExpiresAt_returns409() {
        long productId = product(1_000, 5);
        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1)));
        sleepUntil(expiresAt(o).plusMillis(20));

        ResponseEntity<String> r = pay(o.get("id").asLong(), uid("pk"), "tok");

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).isZero();
    }

    @Test
    @DisplayName("R7.4 만료된 주문을 취소하려 하면 409 INVALID_STATE")
    void r7_4_cancelAfterExpiry_returns409() {
        long productId = product(1_000, 5);
        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1)));
        long orderId = o.get("id").asLong();
        assertThat(awaitStatus(orderId, "EXPIRED", Duration.ofSeconds(8))).isNotNull();

        assertProblem(cancel(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R8.2 만료된 주문은 배송할 수 없다 (409 INVALID_STATE)")
    void r8_2_shipAfterExpiry_returns409() {
        long productId = product(1_000, 5);
        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1)));
        long orderId = o.get("id").asLong();
        assertThat(awaitStatus(orderId, "EXPIRED", Duration.ofSeconds(8))).isNotNull();

        assertProblem(ship(orderId), 409, "INVALID_STATE");
    }
}
