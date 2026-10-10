package com.example.order.api;

import static com.example.order.api.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.order.OrderExpiryService;
import com.example.order.support.ApiClient;
import com.example.order.support.Containers;
import com.example.order.support.TestClock;
import com.example.order.support.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * REQ-13 (+ REQ-16 TTL override): deterministic expiry with an adjustable Clock bean. The scheduler is disabled and
 * {@link OrderExpiryService#expireDueOrders} is called directly. The PG URL points at a closed port, so any test
 * that unexpectedly reaches the gateway fails with 502 instead of the asserted 409.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "order.expiry-sweep-enabled=false", "order.payment-ttl=PT30M", "payment.gateway.url=http://127.0.0.1:1" })
@Import(TestClockConfig.class)
@DisplayName("REQ-13 order payment TTL expiry (adjustable clock, TTL=PT30M)")
class OrderExpiryTest {

    static {
        Containers.postgres();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.register(registry, Containers.postgres());
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestClock clock;
    @Autowired
    OrderExpiryService expiryService;

    ApiClient api;

    @BeforeEach
    void init() {
        clock.reset();
        api = new ApiClient(rest);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private String statusInDb(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    /** Sweeps until nothing is due (other tests' orders share the database). */
    private int sweepAll() {
        int total = 0;
        int n;
        do {
            n = expiryService.expireDueOrders(clock.instant(), 100);
            total += n;
        } while (n > 0);
        return total;
    }

    @Test
    @DisplayName("REQ-08/16 ORDER_PAYMENT_TTL=PT30M 오버라이드 -> expiresAt - createdAt = 30분")
    void ttlOverrideIsAppliedToExpiresAt() {
        JsonNode o = api.orderOk(api.newProduct(1000, 10), 1);

        Duration ttl = Duration.between(Instant.parse(o.get("createdAt").asText()),
                Instant.parse(o.get("expiresAt").asText()));
        assertThat(ttl).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("REQ-13 TTL 이내에는 EXPIRED 가 되지 않는다 (29분 경과 후에도 PENDING_PAYMENT)")
    void notExpiredBeforeTtl() {
        long p = api.newProduct(1000, 10);
        long orderId = api.orderOk(p, 2).get("id").asLong();
        clock.advance(Duration.ofMinutes(29));

        JsonNode fetched = api.order(orderId);
        sweepAll();

        assertThat(fetched.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(statusInDb(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(api.product(p).get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-13 lazy: TTL 경과 후 GET 만으로 EXPIRED 가 영속 반영되고 재고 예약/쿠폰 사용이 해제된다")
    void lazyExpiryOnGetReleasesStockAndCoupon() {
        long p = api.newProduct(1000, 10);
        String coupon = api.newCoupon("FIXED", 100, null, null, 5);
        long orderId = api.orderOk(ApiClient.uniq("u"), p, 3, coupon).get("id").asLong();
        clock.advance(Duration.ofMinutes(31));

        JsonNode fetched = api.order(orderId);

        assertThat(fetched.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(statusInDb(orderId)).isEqualTo("EXPIRED");
        assertThat(api.product(p).get("reserved").asInt()).isZero();
        assertThat(api.product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(api.coupon(coupon).get("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("REQ-13 lazy 만료 후 반복 조회해도 재고/쿠폰이 이중 해제되지 않는다")
    void lazyExpiryReleasesOnlyOnce() {
        long p = api.newProduct(1000, 10);
        String coupon = api.newCoupon("FIXED", 100, null, null, 5);
        api.orderOk(ApiClient.uniq("keep"), p, 4, coupon);
        long orderId = api.orderOk(ApiClient.uniq("u"), p, 3, coupon).get("id").asLong();
        clock.advance(Duration.ofMinutes(31));

        api.order(orderId);
        api.order(orderId);
        sweepAll();

        // the other (also due) order was expired by the sweep as well -> everything released exactly once
        assertThat(api.product(p).get("reserved").asInt()).isZero();
        assertThat(api.coupon(coupon).get("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("REQ-13 scheduler 경로: expireDueOrders 는 기한이 지난 주문만 EXPIRED 로 전환하고 새 주문은 건드리지 않는다")
    void sweepExpiresOnlyDueOrders() {
        long p = api.newProduct(1000, 10);
        long oldOrder = api.orderOk(p, 2).get("id").asLong();
        clock.advance(Duration.ofMinutes(20));
        long youngOrder = api.orderOk(p, 3).get("id").asLong();
        clock.advance(Duration.ofMinutes(15)); // old: 35m (due), young: 15m (not due)

        int expired = sweepAll();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(statusInDb(oldOrder)).isEqualTo("EXPIRED");
        assertThat(statusInDb(youngOrder)).isEqualTo("PENDING_PAYMENT");
        assertThat(api.product(p).get("reserved").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("REQ-13 sweep 은 재실행해도 안전하다 (두 번째 실행은 해당 주문을 다시 건드리지 않음)")
    void sweepIsIdempotent() {
        long p = api.newProduct(1000, 10);
        api.orderOk(p, 2);
        clock.advance(Duration.ofMinutes(31));
        sweepAll();

        int second = sweepAll();

        assertThat(second).isZero();
        assertThat(api.product(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("REQ-13 PAID 주문은 시간이 지나도 만료되지 않는다")
    void paidOrderIsNeverExpired() {
        long p = api.newProduct(1000, 10);
        long orderId = api.orderOk(p, 1).get("id").asLong();
        jdbc.update("update orders set status = 'PAID', paid_at = now() where id = ?", orderId);
        clock.advance(Duration.ofHours(2));

        sweepAll();

        assertThat(api.order(orderId).get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("REQ-13/10 만료 후 pay -> 409 order-expired (orderId, expiresAt), PG 호출 없음, EXPIRED 가 커밋되고 재고/쿠폰 복구")
    void payAfterExpiryIsOrderExpiredAndCommitsTheTransition() {
        long p = api.newProduct(1000, 10);
        String coupon = api.newCoupon("FIXED", 100, null, null, 5);
        JsonNode order = api.orderOk(ApiClient.uniq("u"), p, 2, coupon);
        long orderId = order.get("id").asLong();
        clock.advance(Duration.ofMinutes(31));

        ResponseEntity<JsonNode> res = api.pay(orderId, ApiClient.uniq("pay"), "tok_ok");

        assertProblem(res, 409, "order-expired");
        assertThat(res.getBody().get("orderId").asLong()).isEqualTo(orderId);
        assertThat(Instant.parse(res.getBody().get("expiresAt").asText()))
                .isEqualTo(Instant.parse(order.get("expiresAt").asText()));
        assertThat(statusInDb(orderId)).isEqualTo("EXPIRED");
        assertThat(api.product(p).get("reserved").asInt()).isZero();
        assertThat(api.coupon(coupon).get("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("REQ-13 이미 EXPIRED 가 된 주문에 새 키로 pay -> 계속 409 order-expired")
    void payOnAlreadyExpiredOrderStaysOrderExpired() {
        long orderId = api.orderOk(api.newProduct(1000, 10), 1).get("id").asLong();
        clock.advance(Duration.ofMinutes(31));
        api.order(orderId); // lazy expiry

        ResponseEntity<JsonNode> res = api.pay(orderId, ApiClient.uniq("pay"), "tok_ok");

        assertProblem(res, 409, "order-expired");
    }

    @Test
    @DisplayName("REQ-13 만료 주문 cancel -> 409 invalid-order-state (currentStatus=EXPIRED), 상태 유지")
    void cancelAfterExpiryIsConflict() {
        long p = api.newProduct(1000, 10);
        long orderId = api.orderOk(p, 2).get("id").asLong();
        clock.advance(Duration.ofMinutes(31));

        ResponseEntity<JsonNode> res = api.cancel(orderId);

        assertProblem(res, 409, "invalid-order-state");
        assertThat(res.getBody().get("currentStatus").asText()).isEqualTo("EXPIRED");
        assertThat(statusInDb(orderId)).isEqualTo("EXPIRED");
        assertThat(api.product(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("REQ-13/14 만료 주문 ship / deliver -> 409 invalid-order-state (currentStatus=EXPIRED)")
    void shipAndDeliverAfterExpiryAreConflicts() {
        long orderId = api.orderOk(api.newProduct(1000, 10), 1).get("id").asLong();
        clock.advance(Duration.ofMinutes(31));

        ResponseEntity<JsonNode> ship = api.ship(orderId);
        ResponseEntity<JsonNode> deliver = api.deliver(orderId);

        assertProblem(ship, 409, "invalid-order-state");
        assertThat(ship.getBody().get("currentStatus").asText()).isEqualTo("EXPIRED");
        assertProblem(deliver, 409, "invalid-order-state");
    }

    @Test
    @DisplayName("REQ-13/09 목록 조회가 만료를 반영한다 (status=EXPIRED 에 포함, PENDING_PAYMENT 에서 제외)")
    void listReflectsExpiry() {
        String user = ApiClient.uniq("lst");
        long p = api.newProduct(1000, 10);
        long orderId = api.orderOk(user, p, 1, null).get("id").asLong();
        clock.advance(Duration.ofMinutes(31));

        JsonNode expired = api.get("/api/orders?userId=" + user + "&status=EXPIRED").getBody();
        JsonNode pending = api.get("/api/orders?userId=" + user + "&status=PENDING_PAYMENT").getBody();

        assertThat(expired.get("content")).hasSize(1);
        assertThat(expired.get("content").get(0).get("id").asLong()).isEqualTo(orderId);
        assertThat(pending.get("content")).isEmpty();
    }

    @Test
    @DisplayName("REQ-13 만료로 해제된 재고와 쿠폰 수량은 다시 주문에 쓸 수 있다")
    void releasedResourcesAreReusable() {
        long p = api.newProduct(1000, 2);
        String coupon = api.newCoupon("FIXED", 100, null, null, 1);
        api.orderOk(ApiClient.uniq("u"), p, 2, coupon);
        clock.advance(Duration.ofMinutes(31));
        sweepAll();

        ResponseEntity<JsonNode> again = api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), coupon,
                ApiClient.item(p, 2));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("REQ-11 PG 연결 거부 -> 502 payment-gateway-error reason=UNAVAILABLE retryable=true, 주문/키 불변")
    void connectionRefusedIs502Unavailable() {
        long p = api.newProduct(1000, 10);
        long orderId = api.orderOk(p, 2).get("id").asLong();
        String key = ApiClient.uniq("pay");

        ResponseEntity<JsonNode> res = api.pay(orderId, key, "tok_ok");

        assertProblem(res, 502, "payment-gateway-error");
        assertThat(res.getBody().get("reason").asText()).isEqualTo("UNAVAILABLE");
        assertThat(res.getBody().get("retryable").asBoolean()).isTrue();
        assertThat(statusInDb(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(api.product(p).get("reserved").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = ?", Integer.class, key))
                .isZero();
    }

    @Test
    @DisplayName("REQ-06 쿠폰 유효기간은 Clock 기준: 유효기간 안에서 발급한 쿠폰도 시간이 지나 만료되면 EXPIRED 로 거부")
    void couponExpiresWithTheClock() {
        String code = ApiClient.uniq("SOON");
        Instant until = Instant.now().plus(Duration.ofHours(1));
        api.post("/api/coupons", api.couponBody(code, "FIXED", 100, null, null, 5, "2020-01-01T00:00:00Z",
                until.toString()));
        long p = api.newProduct(1000, 10);
        ResponseEntity<JsonNode> before = api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), code,
                ApiClient.item(p, 1));
        clock.advance(Duration.ofHours(2));

        ResponseEntity<JsonNode> after = api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), code,
                ApiClient.item(p, 1));

        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertProblem(after, 409, "coupon-not-applicable");
        assertThat(after.getBody().get("reason").asText()).isEqualTo("EXPIRED");
    }
}
