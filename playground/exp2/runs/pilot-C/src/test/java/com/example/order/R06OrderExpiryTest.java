package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 짧은 TTL(PT3S) 컨텍스트. 기본 컨텍스트와 구분되는 유일한 추가 컨텍스트다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "order.payment-ttl=PT3S")
@DisplayName("R6 결제 만료 (TTL=PT3S)")
class R06OrderExpiryTest extends AbstractIntegrationTest {

    /** expiresAt + 2초(R6.2)를 데드라인으로 condition 이 참이 될 때까지 기다린다. */
    private Instant awaitWithinDeadline(Instant expiresAt, java.util.concurrent.Callable<Boolean> condition) {
        Instant deadline = expiresAt.plusSeconds(2);
        long remainingMs = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
        Awaitility.await().atMost(Duration.ofMillis(remainingMs)).pollInterval(Duration.ofMillis(50))
                .pollDelay(Duration.ZERO).until(condition);
        return Instant.now();
    }

    private void sleepUntil(Instant t) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20))
                .until(() -> !Instant.now().isBefore(t));
    }

    @Test
    @DisplayName("R3.5 이 컨텍스트에서 expiresAt = createdAt + 3초")
    void expiresAt_reflectsConfiguredTtl() {
        long p = newProduct(1_000, 5);

        ApiResponse r = newOrder(uniqueUser(), null, p, 1);

        assertThat(Duration.between(instant(r.json("createdAt")), instant(r.json("expiresAt"))))
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("R6.1/R6.2 expiresAt 후 2초 안에 주문 EXPIRED, 상품 reserved·쿠폰 usedCount 복원이 조회에 반영")
    void pendingOrder_expiresWithin2Seconds_andRestores() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse order = newOrder(uniqueUser(), coupon, p, 4);
        Instant expiresAt = instant(order.json("expiresAt"));
        assertThat(reservedOf(p)).isEqualTo(4);
        assertThat(usedCountOf(coupon)).isEqualTo(1);

        Instant observedAt = awaitWithinDeadline(expiresAt, () ->
                "EXPIRED".equals(statusOf(order.id())) && reservedOf(p) == 0 && usedCountOf(coupon) == 0);

        assertThat(observedAt).isAfterOrEqualTo(expiresAt);
        assertThat(stockOf(p)).isEqualTo(10);
        assertThat(getProduct(p).json("available").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R6.1 여러 상품 주문도 만료 시 모든 상품 예약이 복원된다")
    void multiProductOrder_restoresAll() {
        long a = newProduct(1_000, 10);
        long b = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, a, 2, b, 3);
        Instant expiresAt = instant(order.json("expiresAt"));

        awaitWithinDeadline(expiresAt, () -> "EXPIRED".equals(statusOf(order.id())));

        assertThat(reservedOf(a)).isZero();
        assertThat(reservedOf(b)).isZero();
    }

    @Test
    @DisplayName("R6.1 만료된 주문의 재고는 다른 주문이 예약할 수 있다 (409 -> 만료 후 201)")
    void expiredReservation_canBeReservedByOthers() {
        long p = newProduct(1_000, 1);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        assertThat(createOrder(uniqueUser(), uniqueKey(), null, p, 1).status()).isEqualTo(409);
        Instant expiresAt = instant(order.json("expiresAt"));

        awaitWithinDeadline(expiresAt, () -> reservedOf(p) == 0);

        assertThat(createOrder(uniqueUser(), uniqueKey(), null, p, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 만료(EXPIRED) 후 같은 사용자가 같은 쿠폰을 다시 쓸 수 있다")
    void expiry_restoresCouponForSameUser() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 1);
        String user = uniqueUser();
        ApiResponse order = newOrder(user, coupon, p, 1);
        assertThat(createOrder(user, uniqueKey(), coupon, p, 1).status()).isEqualTo(409);

        awaitWithinDeadline(instant(order.json("expiresAt")), () -> usedCountOf(coupon) == 0);

        assertThat(createOrder(user, uniqueKey(), coupon, p, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R6.1 PAID 주문은 만료되지 않는다")
    void paidOrder_notExpired() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse order = newPaidOrder(uniqueUser(), coupon, p, 2);
        Instant expiresAt = instant(order.json("expiresAt"));

        sleepUntil(expiresAt.plusMillis(1_500));

        assertThat(statusOf(order.id())).isEqualTo("PAID");
        assertThat(stockOf(p)).isEqualTo(8);
        assertThat(reservedOf(p)).isZero();
        assertThat(usedCountOf(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R6.1 CANCELLED / PAYMENT_FAILED 주문은 EXPIRED 로 바뀌지 않고 복원이 중복되지 않는다")
    void terminalOrders_notTouchedByExpiry() {
        long p = newProduct(1_000, 20);
        String coupon = newCoupon("FIXED", 100, null, null, 5);
        ApiResponse cancelled = newOrder(uniqueUser(), coupon, p, 3);
        cancel(cancelled.id());
        ApiResponse failed = newOrder(uniqueUser(), coupon, p, 4);
        stubPgPayment("DECLINED", "pay-no-exp");
        assertThat(pay(failed.id(), uniqueKey(), "tok").status()).isEqualTo(402);
        ApiResponse holder = newOrder(uniqueUser(), coupon, p, 5); // 이 주문만 만료되어야 한다
        assertThat(reservedOf(p)).isEqualTo(5);

        sleepUntil(instant(holder.json("expiresAt")).plusMillis(1_500));

        assertThat(statusOf(cancelled.id())).isEqualTo("CANCELLED");
        assertThat(statusOf(failed.id())).isEqualTo("PAYMENT_FAILED");
        assertThat(statusOf(holder.id())).isEqualTo("EXPIRED");
        assertThat(reservedOf(p)).isZero();
        assertThat(usedCountOf(coupon)).isZero();
    }

    @Test
    @DisplayName("R5.2 expiresAt 이 지난 주문 결제 -> 409 INVALID_STATE (스윕 여부와 무관), PG 미호출, 예약 유지/복원 외 변화 없음")
    void pay_afterExpiresAt_409() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("APPROVED", "pay-late");
        sleepUntil(instant(order.json("expiresAt")));

        ApiResponse r = pay(order.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(pgPaymentRequestCount()).isZero();
        assertThat(stockOf(p)).isEqualTo(10);
        assertThat(statusOf(order.id())).isIn("PENDING_PAYMENT", "EXPIRED");
    }

    @Test
    @DisplayName("R7.4/R8.2 EXPIRED 주문의 cancel/ship/deliver -> 409 INVALID_STATE")
    void expiredOrder_otherTransitions_409() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        awaitWithinDeadline(instant(order.json("expiresAt")), () -> "EXPIRED".equals(statusOf(order.id())));

        ApiResponse c = cancel(order.id());
        ApiResponse s = ship(order.id());
        ApiResponse d = deliver(order.id());

        assertThat(c.status()).isEqualTo(409);
        assertThat(c.code()).isEqualTo("INVALID_STATE");
        assertThat(s.status()).isEqualTo(409);
        assertThat(d.status()).isEqualTo(409);
        assertThat(statusOf(order.id())).isEqualTo("EXPIRED");
    }
}
