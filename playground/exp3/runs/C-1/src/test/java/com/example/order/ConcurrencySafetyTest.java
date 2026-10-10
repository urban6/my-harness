package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.order.OrderExpirySweeper;
import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.Concurrently;
import com.example.order.support.Concurrently.Resp;
import com.example.order.support.InvariantChecker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * R19: real PostgreSQL (Testcontainers), real threads released together through a latch.
 * Every test ends with the 02_db_design.md section 5 invariants checked straight from the database.
 */
class ConcurrencySafetyTest extends AbstractIntegrationTest {

    @Autowired
    OrderExpirySweeper sweeper;

    @AfterEach
    void invariantsHoldInDatabase() {
        assertThat(InvariantChecker.violations(jdbc)).as("02 section 5 invariants").isEmpty();
    }

    private String statusOf(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    // ------------------------------------------------------------------ creation

    @Test
    @DisplayName("R19/R05 40 concurrent single-unit orders on stock 10: exactly 10 succeed, reserved never exceeds stock")
    void concurrentOrders_neverOversell() throws Exception {
        long product = api.createProduct("Hot", 1000, 10);

        List<Resp> responses = Concurrently.run(40, objectMapper,
                i -> () -> api.postOrder("user-" + i, "key-" + i, api.orderBody(null, product, 1)));

        assertThat(Concurrently.count(responses, 201)).isEqualTo(10);
        assertThat(Concurrently.count(responses, 409, "insufficient-stock")).isEqualTo(30);
        assertThat(reserved(product)).isEqualTo(10);
        assertThat(stock(product)).isEqualTo(10);
        assertThat(orderCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("R19/R05 20 concurrent 3-unit orders on stock 10: only 3 succeed (reserved 9, never above stock)")
    void concurrentMultiUnitOrders_neverOversell() throws Exception {
        long product = api.createProduct("Hot", 1000, 10);

        List<Resp> responses = Concurrently.run(20, objectMapper,
                i -> () -> api.postOrder("user-" + i, "key-" + i, api.orderBody(null, product, 3)));

        assertThat(Concurrently.count(responses, 201)).isEqualTo(3);
        assertThat(Concurrently.count(responses, 409, "insufficient-stock")).isEqualTo(17);
        assertThat(reserved(product)).isEqualTo(9);
    }

    @Test
    @DisplayName("R19/R05 orders that list the same products in opposite order never deadlock (lock order is by product id)")
    void concurrentOrdersWithOppositeItemOrder_doNotDeadlock() throws Exception {
        long a = api.createProduct("A", 100, 100);
        long b = api.createProduct("B", 100, 100);

        List<Resp> responses = Concurrently.run(30, objectMapper, i -> () -> api.postOrder("user-" + i, "key-" + i,
                i % 2 == 0 ? api.orderBody(null, a, 1, b, 1) : api.orderBody(null, b, 1, a, 1)));

        assertThat(Concurrently.count(responses, 201)).isEqualTo(30);
        assertThat(reserved(a)).isEqualTo(30);
        assertThat(reserved(b)).isEqualTo(30);
    }

    @Test
    @DisplayName("R19/R06 25 concurrent orders on a 5-use coupon: exactly 5 use it, used_count never exceeds total_quantity")
    void concurrentOrders_neverExceedCouponQuantity() throws Exception {
        long product = api.createProduct("Hot", 1000, 100);
        api.createCoupon("FIVE", "FIXED", 100, null, null, 5);

        List<Resp> responses = Concurrently.run(25, objectMapper,
                i -> () -> api.postOrder("user-" + i, "key-" + i, api.orderBody("FIVE", product, 1)));

        assertThat(Concurrently.count(responses, 201)).isEqualTo(5);
        assertThat(Concurrently.count(responses, 422, "coupon-exhausted")).isEqualTo(20);
        assertThat(couponUsed("FIVE")).isEqualTo(5);
        assertThat(reserved(product)).as("rolled-back orders hold no stock").isEqualTo(5);
        assertThat(orderCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("R19/R05/R06 stock and coupon limits racing together: a stock failure never leaks a coupon use")
    void concurrentOrders_stockFailureRollsBackCouponUse() throws Exception {
        long product = api.createProduct("Scarce", 1000, 3);
        api.createCoupon("PLENTY", "FIXED", 100, null, null, 50);

        List<Resp> responses = Concurrently.run(20, objectMapper,
                i -> () -> api.postOrder("user-" + i, "key-" + i, api.orderBody("PLENTY", product, 1)));

        assertThat(Concurrently.count(responses, 201)).isEqualTo(3);
        assertThat(Concurrently.count(responses, 409, "insufficient-stock")).isEqualTo(17);
        assertThat(couponUsed("PLENTY")).isEqualTo(3);
        assertThat(reserved(product)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ idempotency under concurrency

    @Test
    @DisplayName("R19/R07 16 concurrent requests with the same Idempotency-Key and body create exactly one order")
    void concurrentSameKeySameBody_createsOneOrder() throws Exception {
        long product = api.createProduct("Hot", 1000, 10);
        api.createCoupon("ONE", "FIXED", 100, null, null, 5);

        List<Resp> responses = Concurrently.run(16, objectMapper,
                i -> () -> api.postOrder("same-user", "same-key", api.orderBody("ONE", product, 2)));

        assertThat(Concurrently.count(responses, 201)).isEqualTo(16);
        Set<Long> ids = responses.stream().map(r -> r.body().get("id").asLong()).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(responses.stream().filter(r -> !r.replayed()).count()).as("only one non-replayed response").isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(product)).isEqualTo(2);
        assertThat(couponUsed("ONE")).isEqualTo(1);
    }

    @Test
    @DisplayName("R19/R07 concurrent requests with one key but two different bodies: one order, the other body gets 409")
    void concurrentSameKeyDifferentBodies_oneWinsOthersConflict() throws Exception {
        long product = api.createProduct("Hot", 1000, 50);

        List<Resp> responses = Concurrently.run(10, objectMapper,
                i -> () -> api.postOrder("same-user", "same-key", api.orderBody(null, product, 1 + (i % 2))));

        assertThat(orderCount()).isEqualTo(1);
        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
        assertThat(responses.stream().filter(r -> r.status() == 409).map(Resp::slug).distinct())
                .allMatch("idempotency-key-conflict"::equals);
        assertThat(responses.stream().filter(r -> r.status() == 201 && !r.replayed()).count()).isEqualTo(1);
        long winnerQty = jdbc.queryForObject("SELECT quantity FROM order_items", Long.class);
        assertThat(reserved(product)).isEqualTo((int) winnerQty);
    }

    // ------------------------------------------------------------------ pay / cancel / expire races

    @Test
    @DisplayName("R19/R10 8 concurrent pay calls with the same key: one PG charge, one non-replayed 200, order PAID once")
    void concurrentPaySameKey_chargesOnce() throws Exception {
        long product = api.createProduct("Hot", 1000, 10);
        long orderId = api.createOrder("u", "k", null, product, 2).get("id").asLong();
        GATEWAY.delayMillis(300);

        List<Resp> responses = Concurrently.run(8, objectMapper, i -> () -> api.pay(orderId, "pay-1", "tok"));

        assertThat(GATEWAY.charges()).hasSize(1);
        assertThat(responses.stream().filter(r -> r.status() == 200 && !r.replayed()).count()).isEqualTo(1);
        // losers are either replays of the final result or told to retry; narrow read-then-claim races may also
        // surface as invalid-order-state; nothing else is acceptable
        assertThat(responses.stream().filter(r -> r.status() != 200).map(Resp::slug).distinct())
                .isSubsetOf("operation-in-progress", "invalid-order-state");
        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(200, 409));
        assertThat(statusOf(orderId)).isEqualTo("PAID");
        assertThat(reserved(product)).isEqualTo(2);
    }

    @Test
    @DisplayName("R19/R10 6 concurrent pay calls with different keys: only the first key charges the PG")
    void concurrentPayDifferentKeys_chargesOnce() throws Exception {
        long product = api.createProduct("Hot", 1000, 10);
        long orderId = api.createOrder("u", "k", null, product, 1).get("id").asLong();
        GATEWAY.delayMillis(300);

        List<Resp> responses = Concurrently.run(6, objectMapper, i -> () -> api.pay(orderId, "pay-" + i, "tok"));

        assertThat(GATEWAY.charges()).hasSize(1);
        assertThat(Concurrently.count(responses, 200)).isEqualTo(1);
        assertThat(Concurrently.count(responses, 409)).isEqualTo(5);
        assertThat(responses.stream().filter(r -> r.status() == 409).map(Resp::slug).distinct())
                .isSubsetOf("idempotency-key-conflict", "operation-in-progress", "invalid-order-state");
        assertThat(statusOf(orderId)).isEqualTo("PAID");
    }

    @RepeatedTest(8)
    @DisplayName("R19/R15 pay racing with cancel: one consistent final state, reservations and coupon returned at most once")
    void concurrentPayAndCancel_singleFinalState() throws Exception {
        long product = api.createProduct("Hot", 1000, 20);
        api.createCoupon("C", "FIXED", 100, null, null, 10);
        api.createOrder("bystander", "bk", "C", product, 3); // detects any double release
        long orderId = api.createOrder("u", "k", "C", product, 2).get("id").asLong();
        GATEWAY.delayMillis(150);

        List<Callable<Resp>> tasks = new ArrayList<>();
        tasks.add(() -> Concurrently.snapshot(api.pay(orderId, "pay-1", "tok"), objectMapper));
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> Concurrently.snapshot(api.action(orderId, "cancel"), objectMapper));
        }
        List<Resp> responses = Concurrently.runTasks(tasks);
        Resp pay = responses.get(0);
        List<Resp> cancels = responses.subList(1, 4);

        String status = statusOf(orderId);
        assertThat(status).isIn("PAID", "CANCELLED", "REFUNDED");
        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(200, 409));
        long cancelSuccesses = cancels.stream().filter(r -> r.status() == 200).count();
        switch (status) {
            case "PAID" -> {
                assertThat(pay.status()).isEqualTo(200);
                assertThat(cancelSuccesses).isZero();
                assertThat(GATEWAY.charges()).hasSize(1);
                assertThat(GATEWAY.refunds()).isEmpty();
                assertThat(reserved(product)).isEqualTo(5);
                assertThat(couponUsed("C")).isEqualTo(2);
            }
            case "CANCELLED" -> {
                assertThat(pay.status()).isEqualTo(409);
                assertThat(cancelSuccesses).isEqualTo(1);
                assertThat(GATEWAY.charges()).isEmpty();
                assertThat(GATEWAY.refunds()).isEmpty();
                assertThat(reserved(product)).isEqualTo(3);
                assertThat(couponUsed("C")).isEqualTo(1);
            }
            default -> { // paid first, then a cancel refunded it
                assertThat(pay.status()).isEqualTo(200);
                assertThat(cancelSuccesses).isEqualTo(1);
                assertThat(GATEWAY.charges()).hasSize(1);
                assertThat(GATEWAY.refunds()).hasSize(1);
                assertThat(reserved(product)).isEqualTo(3);
                assertThat(couponUsed("C")).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("R19/R13 8 concurrent cancels of a PAID order: PG refund called once, stock and coupon returned once")
    void concurrentCancelOfPaidOrder_refundsOnce() throws Exception {
        long product = api.createProduct("Hot", 1000, 20);
        api.createCoupon("C", "FIXED", 100, null, null, 10);
        api.createOrder("bystander", "bk", "C", product, 3);
        long orderId = api.createPaidOrder("u", "k", "C", product, 2);
        GATEWAY.delayMillis(300);

        List<Resp> responses = Concurrently.run(8, objectMapper, i -> () -> api.action(orderId, "cancel"));

        assertThat(GATEWAY.refunds()).hasSize(1);
        assertThat(Concurrently.count(responses, 200)).isEqualTo(1);
        assertThat(Concurrently.count(responses, 409)).isEqualTo(7);
        assertThat(responses.stream().filter(r -> r.status() == 409).map(Resp::slug).distinct())
                .isSubsetOf("operation-in-progress", "invalid-order-state");
        assertThat(statusOf(orderId)).isEqualTo("REFUNDED");
        assertThat(reserved(product)).as("only the bystander's 3 remain reserved").isEqualTo(3);
        assertThat(couponUsed("C")).isEqualTo(1);
    }

    @Test
    @DisplayName("R19/R13 10 concurrent cancels of a PENDING order: one CANCELLED, release happens once, no PG call")
    void concurrentCancelOfPendingOrder_releasesOnce() throws Exception {
        long product = api.createProduct("Hot", 1000, 20);
        api.createCoupon("C", "FIXED", 100, null, null, 10);
        api.createOrder("bystander", "bk", "C", product, 3);
        long orderId = api.createOrder("u", "k", "C", product, 2).get("id").asLong();

        List<Resp> responses = Concurrently.run(10, objectMapper, i -> () -> api.action(orderId, "cancel"));

        assertThat(Concurrently.count(responses, 200)).isEqualTo(1);
        assertThat(Concurrently.count(responses, 409, "invalid-order-state")).isEqualTo(9);
        assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
        assertThat(reserved(product)).isEqualTo(3);
        assertThat(couponUsed("C")).isEqualTo(1);
        assertThat(GATEWAY.requests()).isEmpty();
    }

    @Test
    @DisplayName("R19/R12 lazy expiry (GET, list), the sweeper, cancel and pay racing on one overdue order: released exactly once")
    void concurrentExpiryPaths_releaseOnce() throws Exception {
        long product = api.createProduct("Hot", 1000, 20);
        api.createCoupon("C", "FIXED", 100, null, null, 10);
        long overdue = api.createOrder("u", "k1", "C", product, 2).get("id").asLong();
        clock.advance(Duration.ofMinutes(10));
        api.createOrder("bystander", "bk", "C", product, 3);
        clock.advance(Duration.ofMinutes(6)); // first order is 16 min old, bystander 6 min

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> Concurrently.snapshot(api.getOrder(overdue), objectMapper));
            tasks.add(() -> Concurrently.snapshot(api.listOrders("status=EXPIRED"), objectMapper));
            tasks.add(() -> sweeper.sweepOnce());
        }
        tasks.add(() -> Concurrently.snapshot(api.action(overdue, "cancel"), objectMapper));
        tasks.add(() -> Concurrently.snapshot(api.pay(overdue, "pay-1", "tok"), objectMapper));
        List<Object> results = Concurrently.runTasks(tasks);

        for (Object r : results) {
            if (r instanceof Resp resp) {
                assertThat(resp.status()).isIn(200, 409);
            }
        }
        int sweptTotal = results.stream().filter(Integer.class::isInstance).mapToInt(Integer.class::cast).sum();
        assertThat(sweptTotal).isLessThanOrEqualTo(1);
        assertThat(statusOf(overdue)).isEqualTo("EXPIRED");
        assertThat(reserved(product)).as("only the bystander's 3 remain reserved").isEqualTo(3);
        assertThat(couponUsed("C")).isEqualTo(1);
        assertThat(GATEWAY.charges()).isEmpty();
    }

    @Test
    @DisplayName("R19/R12/R11 an in-flight payment wins over lazy expiry and the sweeper even after expiresAt has passed")
    void payInFlight_isNotExpiredByTtlSweepOrLazyPaths() throws Exception {
        long product = api.createProduct("Hot", 1000, 10);
        JsonNodeHolder order = new JsonNodeHolder(api.createOrder("u", "k", null, product, 2));
        long orderId = order.id();
        Instant expiresAt = Instant.parse(order.expiresAt());
        clock.set(expiresAt.minusSeconds(1)); // still payable, the lease will outlive expiresAt
        GATEWAY.delayMillis(800);

        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> Concurrently.snapshot(api.pay(orderId, "pay-1", "tok"), objectMapper));
        tasks.add(() -> {
            long deadline = System.currentTimeMillis() + 5000;
            while (GATEWAY.charges().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(100);
            clock.advance(Duration.ofSeconds(5)); // now past expiresAt, lease (30 s) still active
            int swept = sweeper.sweepOnce();
            Resp get = Concurrently.snapshot(api.getOrder(orderId), objectMapper);
            Resp cancel = Concurrently.snapshot(api.action(orderId, "cancel"), objectMapper);
            return List.of(swept, get, cancel);
        });
        List<Object> results = Concurrently.runTasks(tasks);

        Resp pay = (Resp) results.get(0);
        @SuppressWarnings("unchecked")
        List<Object> observer = (List<Object>) results.get(1);
        assertThat(observer.get(0)).as("sweeper must skip the leased order").isEqualTo(0);
        assertThat(((Resp) observer.get(1)).body().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(((Resp) observer.get(2)).status()).isEqualTo(409);
        assertThat(((Resp) observer.get(2)).slug()).isEqualTo("operation-in-progress");
        assertThat(((Resp) observer.get(2)).retryAfter()).isEqualTo("1");
        assertThat(pay.status()).isEqualTo(200);
        assertThat(pay.body().get("status").asText()).isEqualTo("PAID");
        assertThat(reserved(product)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ mixed workload

    @Test
    @DisplayName("R19/R15 mixed random workload (create/pay/cancel/ship/deliver/read/sweep) never produces 5xx and keeps all invariants")
    void mixedWorkload_keepsInvariants() throws Exception {
        long p1 = api.createProduct("P1", 1000, 25);
        long p2 = api.createProduct("P2", 2000, 25);
        api.createCoupon("MIX", "RATE", 10, null, 500L, 12);
        List<Long> pool = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            pool.add(api.createOrder("pool", "pk-" + i, i % 2 == 0 ? "MIX" : null, i % 3 == 0 ? p1 : p2, 1)
                    .get("id").asLong());
        }
        for (int i = 0; i < 6; i++) {
            api.pay(pool.get(i), "pool-pay-" + i, "tok").andReturn();
        }
        int initialStock1 = stock(p1);
        int initialStock2 = stock(p2);

        Random random = new Random(42);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            final int n = i;
            final long target = pool.get(random.nextInt(pool.size()));
            final int kind = random.nextInt(8);
            tasks.add(() -> switch (kind) {
                case 0, 1 -> Concurrently.snapshot(api.postOrder("mix-" + n, "mk-" + n,
                        api.orderBody(n % 2 == 0 ? "MIX" : null, p1, 1 + n % 2, p2, 1)), objectMapper);
                case 2 -> Concurrently.snapshot(api.pay(target, "pool-pay-x" + n, "tok"), objectMapper);
                case 3 -> Concurrently.snapshot(api.action(target, "cancel"), objectMapper);
                case 4 -> Concurrently.snapshot(api.action(target, "ship"), objectMapper);
                case 5 -> Concurrently.snapshot(api.action(target, "deliver"), objectMapper);
                case 6 -> Concurrently.snapshot(api.getOrder(target), objectMapper);
                default -> sweeper.sweepOnce();
            });
        }
        List<Object> results = Concurrently.runTasks(tasks);

        for (Object r : results) {
            if (r instanceof Resp resp) {
                assertThat(resp.status()).as("no 5xx: " + resp.body()).isLessThan(500);
                assertThat(resp.status()).isIn(200, 201, 404, 409, 422);
            }
        }
        // physical stock only ever drops through ship: stock == initial - shipped quantity
        for (long product : List.of(p1, p2)) {
            long shipped = jdbc.queryForObject("""
                    SELECT COALESCE(sum(i.quantity), 0) FROM order_items i JOIN orders o ON o.id = i.order_id
                     WHERE i.product_id = ? AND o.status IN ('SHIPPED','DELIVERED')
                    """, Long.class, product);
            int initial = product == p1 ? initialStock1 : initialStock2;
            assertThat((long) stock(product)).isEqualTo(initial - shipped);
        }
    }

    /** Tiny reader so the test body stays readable. */
    private record JsonNodeHolder(com.fasterxml.jackson.databind.JsonNode node) {
        long id() {
            return node.get("id").asLong();
        }

        String expiresAt() {
            return node.get("expiresAt").asText();
        }
    }
}
