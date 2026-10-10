package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ClockTestConfig;
import com.example.order.support.IntegrationTestBase;
import com.example.order.support.MutableClock;
import com.example.order.support.PostgresTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 시각 경계값 테스트. 앱의 Clock 을 {@link MutableClock} 으로 바꿔 마이크로초 단위 경계를 결정적으로 확인한다.
 * 만료 스케줄러는 꺼 두고(1시간 간격) 지연 평가(조회·결제·취소 시점 판정)만 검증한다.
 */
@Import({PostgresTestConfig.class, ClockTestConfig.class})
@TestPropertySource(properties = "order.expiry.scan-interval=PT1H")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClockBoundaryTest extends IntegrationTestBase {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Autowired
    private MutableClock clock;

    private Instant t0;

    @BeforeEach
    void pinClock() {
        t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(t0);
    }

    private String couponValidBetween(Instant from, Instant until) {
        Map<String, Object> body = couponBody(uniqueCode(), "FIXED", 100);
        body.put("validFrom", from.toString());
        body.put("validUntil", until.toString());
        return createCoupon(body).get("code").asText();
    }

    // ------------------------------------------------------------------ R2.5 쿠폰 기간 경계

    @Test
    @DisplayName("R2.5 요청 시각 == validFrom 이면 쿠폰이 적용된다 (validFrom 포함)")
    void r2_5_validFromIsInclusive() {
        String code = couponValidBetween(t0, t0.plus(1, ChronoUnit.HOURS));
        long productId = newProduct(1000, 10);

        clock.set(t0);

        assertThat(code(placeOrder(uniqueUser(), code, productId, 1))).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 요청 시각이 validFrom 보다 1마이크로초 이르면 409 COUPON_NOT_APPLICABLE")
    void r2_5_justBeforeValidFromRejected() {
        String code = couponValidBetween(t0, t0.plus(1, ChronoUnit.HOURS));
        long productId = newProduct(1000, 10);

        clock.set(t0.minus(1, ChronoUnit.MICROS));

        assertProblem(placeOrder(uniqueUser(), code, productId, 1), 409, "COUPON_NOT_APPLICABLE");
        assertUsedCount(code, 0);
        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R2.5 요청 시각이 validUntil 보다 1마이크로초 이르면 쿠폰이 적용된다")
    void r2_5_justBeforeValidUntilAccepted() {
        Instant until = t0.plus(1, ChronoUnit.HOURS);
        String code = couponValidBetween(t0, until);
        long productId = newProduct(1000, 10);

        clock.set(until.minus(1, ChronoUnit.MICROS));

        assertThat(code(placeOrder(uniqueUser(), code, productId, 1))).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 요청 시각 == validUntil 이면 409 COUPON_NOT_APPLICABLE (validUntil 미포함)")
    void r2_5_validUntilIsExclusive() {
        Instant until = t0.plus(1, ChronoUnit.HOURS);
        String code = couponValidBetween(t0, until);
        long productId = newProduct(1000, 10);

        clock.set(until);

        assertProblem(placeOrder(uniqueUser(), code, productId, 1), 409, "COUPON_NOT_APPLICABLE");
        assertUsedCount(code, 0);
        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R2.5 validUntil 이후 시각에도 409 COUPON_NOT_APPLICABLE")
    void r2_5_afterValidUntilRejected() {
        Instant until = t0.plus(1, ChronoUnit.HOURS);
        String code = couponValidBetween(t0, until);

        clock.set(until.plus(1, ChronoUnit.DAYS));

        assertProblem(placeOrder(uniqueUser(), code, newProduct(1000, 10), 1), 409, "COUPON_NOT_APPLICABLE");
    }

    // ------------------------------------------------------------------ R3.5 생성 시각

    @Test
    @DisplayName("R3.5/C2 createdAt 은 요청 시각이고 expiresAt = createdAt + 15분(기본 TTL) 이다")
    void r3_5_createdAtAndExpiresAtFollowClock() {
        JsonNode order = newOrder(newProduct(1000, 5), 1);

        assertThat(time(order, "createdAt").toInstant()).isEqualTo(t0);
        assertThat(time(order, "expiresAt").toInstant()).isEqualTo(t0.plus(TTL));
    }

    @Test
    @DisplayName("R5.4/C2 paidAt 은 결제 처리 시각이다")
    void r5_4_paidAtFollowsClock() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        Instant payTime = t0.plusSeconds(90);
        clock.set(payTime);

        JsonNode paid = payOk(orderId);

        assertThat(time(paid, "paidAt").toInstant()).isEqualTo(payTime);
    }

    // ------------------------------------------------------------------ R5.2 / R6 / R7.4 만료 경계(지연 평가)

    @Test
    @DisplayName("R5.2 expiresAt 직전(1초 전)에는 결제가 된다")
    void r5_2_payBeforeExpiryAccepted() {
        long orderId = newOrder(newProduct(1000, 5), 1).get("id").asLong();
        clock.set(t0.plus(TTL).minusSeconds(1));

        assertThat(payOk(orderId).get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R5.2 expiresAt 이 지난 뒤(1초 후)의 결제는 409 INVALID_STATE, PG 호출 없음, 주문 EXPIRED + 예약·쿠폰 복원")
    void r5_2_payAfterExpiryRejected() {
        long productId = newProduct(1000, 5);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 2)).get("id").asLong();
        clock.set(t0.plus(TTL).plusSeconds(1));

        ResponseEntity<JsonNode> res = pay(orderId);

        assertProblem(res, 409, "INVALID_STATE");
        assertThat(PG.requests()).isEmpty();
        assertThat(statusOf(orderId)).isEqualTo("EXPIRED");
        assertStock(productId, 5, 0);
        assertUsedCount(code, 0);
    }

    @Test
    @DisplayName("R7.4 expiresAt 이 지난 PENDING_PAYMENT 주문의 취소는 409 INVALID_STATE")
    void r7_4_cancelAfterExpiryRejected() {
        long productId = newProduct(1000, 5);
        long orderId = newOrder(productId, 2).get("id").asLong();
        clock.set(t0.plus(TTL).plusSeconds(1));

        assertProblem(cancel(orderId), 409, "INVALID_STATE");

        assertThat(statusOf(orderId)).isEqualTo("EXPIRED");
        assertStock(productId, 5, 0);
    }

    @Test
    @DisplayName("R6.1 expiresAt 이 지난 뒤 주문 조회는 EXPIRED, 직전 조회는 PENDING_PAYMENT")
    void r6_1_readReflectsExpiryByClock() {
        long productId = newProduct(1000, 5);
        long orderId = newOrder(productId, 2).get("id").asLong();

        clock.set(t0.plus(TTL).minusSeconds(1));
        assertThat(statusOf(orderId)).isEqualTo("PENDING_PAYMENT");
        assertStock(productId, 5, 2);

        clock.set(t0.plus(TTL).plusSeconds(1));
        assertThat(statusOf(orderId)).isEqualTo("EXPIRED");
        assertStock(productId, 5, 0);
    }

    @Test
    @DisplayName("R6.1 결제로 PAID 가 된 주문은 expiresAt 이 지나도 EXPIRED 가 되지 않는다")
    void r6_1_paidOrderNeverExpires() {
        long productId = newProduct(1000, 5);
        long orderId = newOrder(productId, 2).get("id").asLong();
        payOk(orderId);

        clock.set(t0.plus(TTL).plus(1, ChronoUnit.DAYS));

        assertThat(statusOf(orderId)).isEqualTo("PAID");
        assertStock(productId, 3, 0);
    }

    // ------------------------------------------------------------------ R9.4 / R9.5 동일 createdAt

    @Test
    @DisplayName("R9.4 시각이 고정되어 createdAt 이 모두 같을 때 id 내림차순이고, 페이지 순회도 중복·누락이 없다")
    void r9_4_identicalCreatedAtOrderedByIdDesc() {
        String user = uniqueUser();
        long productId = newProduct(1000, 100);
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            created.add(newOrder(user, null, items(productId, 1)).get("id").asLong());
        }
        List<Long> expected = new ArrayList<>(created);
        java.util.Collections.reverse(expected);

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = get("/api/orders?userId=" + user + "&size=3" + (cursor == null ? "" : "&cursor=" + cursor))
                    .getBody();
            page.get("content").forEach(n -> {
                assertThat(time(n, "createdAt").toInstant()).isEqualTo(t0);
                seen.add(n.get("id").asLong());
            });
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("R9.5 createdAt 이 같은 주문들을 순회하는 사이 같은 createdAt 의 새 주문이 생겨도 기존 주문은 한 번씩만 나온다")
    void r9_5_newOrderWithSameCreatedAtDoesNotDisturbTraversal() {
        String user = uniqueUser();
        long productId = newProduct(1000, 100);
        List<Long> original = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            original.add(newOrder(user, null, items(productId, 1)).get("id").asLong());
        }
        List<Long> seen = new ArrayList<>();
        JsonNode page = get("/api/orders?userId=" + user + "&size=2").getBody();
        page.get("content").forEach(n -> seen.add(n.get("id").asLong()));

        while (!page.get("nextCursor").isNull()) {
            newOrder(user, null, items(productId, 1)); // 같은 순간(createdAt 동일)에 새 주문
            page = get("/api/orders?userId=" + user + "&size=2&cursor=" + page.get("nextCursor").asText()).getBody();
            page.get("content").forEach(n -> seen.add(n.get("id").asLong()));
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(original);
    }
}
