package com.example.order.api;

import static com.example.order.support.ApiClient.item;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiClient;
import com.example.order.support.IntegrationTestBase;
import com.example.order.support.PgStub.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.ResponseEntity;

@DisplayName("REQ-17 concurrency safety")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ConcurrencyTest extends IntegrationTestBase {

    /** Immutable snapshot of a response (ResponseEntity bodies are fine to share, headers are copied out). */
    record Result(int status, JsonNode body, String replayed) {
        String code() {
            return body != null && body.has("code") ? body.get("code").asText() : "";
        }
    }

    private static Result result(ResponseEntity<JsonNode> r) {
        return new Result(r.getStatusCode().value(), r.getBody(), r.getHeaders().getFirst("Idempotent-Replayed"));
    }

    /** Runs n tasks that all start at the same instant and returns their results in task order. */
    private static List<Result> parallel(int n, IntFunction<Callable<ResponseEntity<JsonNode>>> taskFactory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<ResponseEntity<JsonNode>> task = taskFactory.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return result(task.call());
                }));
            }
            ready.await();
            start.countDown();
            List<Result> results = new ArrayList<>();
            for (Future<Result> f : futures) {
                results.add(f.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<Result> results, int status) {
        return results.stream().filter(r -> r.status() == status).count();
    }

    // ---------------------------------------------------------------- order creation

    @Test
    @DisplayName("REQ-17/05 재고 5개 상품에 20개 동시 주문 -> 정확히 5건 201, 15건 409 insufficient-stock, reserved=5 (오버셀 없음)")
    void concurrentOrdersNeverOversell() throws Exception {
        long p = api.newProduct(1000, 5);

        List<Result> results = parallel(20, i -> () -> api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), null,
                item(p, 1)));

        assertThat(count(results, 201)).isEqualTo(5);
        assertThat(count(results, 409)).isEqualTo(15);
        assertThat(results.stream().filter(r -> r.status() == 409).map(Result::code).collect(Collectors.toSet()))
                .containsExactly("INSUFFICIENT_STOCK");
        JsonNode product = api.product(p);
        assertThat(product.get("reserved").asInt()).isEqualTo(5);
        assertThat(product.get("available").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_items where product_id = ?", Integer.class, p))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("REQ-17/05 수량 3짜리 8개 동시 주문, 재고 10 -> 3건 성공(9개 예약), reserved<=stock")
    void concurrentMultiQuantityOrdersRespectAvailable() throws Exception {
        long p = api.newProduct(1000, 10);

        List<Result> results = parallel(8, i -> () -> api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), null,
                item(p, 3)));

        assertThat(count(results, 201)).isEqualTo(3);
        assertThat(count(results, 409)).isEqualTo(5);
        assertThat(api.product(p).get("reserved").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("REQ-17/05 상품 순서가 반대인 동시 주문 ([A,B] vs [B,A]) 도 교착 없이 모두 성공")
    void oppositeProductOrderDoesNotDeadlock() throws Exception {
        long a = api.newProduct(100, 1000);
        long b = api.newProduct(100, 1000);

        List<Result> results = parallel(20, i -> () -> i % 2 == 0
                ? api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), null, item(a, 1), item(b, 1))
                : api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), null, item(b, 1), item(a, 1)));

        assertThat(count(results, 201)).isEqualTo(20);
        assertThat(api.product(a).get("reserved").asInt()).isEqualTo(20);
        assertThat(api.product(b).get("reserved").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("REQ-17/06 totalQuantity=3 쿠폰에 12개 동시 주문 -> 정확히 3건 성공, 9건 409 coupon-not-applicable, usedCount=3, 실패분 재고 예약 롤백")
    void concurrentOrdersNeverExceedCouponQuantity() throws Exception {
        long p = api.newProduct(1000, 100);
        String coupon = api.newCoupon("FIXED", 100, null, null, 3);

        List<Result> results = parallel(12, i -> () -> api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), coupon,
                item(p, 1)));

        assertThat(count(results, 201)).isEqualTo(3);
        assertThat(count(results, 409)).isEqualTo(9);
        assertThat(results.stream().filter(r -> r.status() == 409).map(Result::code).collect(Collectors.toSet()))
                .containsExactly("COUPON_NOT_APPLICABLE");
        assertThat(results.stream().filter(r -> r.status() == 409)
                .map(r -> r.body().get("reason").asText()).collect(Collectors.toSet())).containsExactly("EXHAUSTED");
        assertThat(api.coupon(coupon).get("usedCount").asInt()).isEqualTo(3);
        assertThat(api.product(p).get("reserved").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("REQ-17/07 같은 Idempotency-Key 10개 동시 요청 -> 주문 1건, 신규 1건 + 재생 9건, 예약/쿠폰 1회")
    void sameIdempotencyKeyConcurrentlyCreatesSingleOrder() throws Exception {
        long p = api.newProduct(1000, 10);
        String coupon = api.newCoupon("FIXED", 100, null, null, 5);
        String user = ApiClient.uniq("u");
        String key = ApiClient.uniq("k");

        List<Result> results = parallel(10, i -> () -> api.placeOrder(user, key, coupon, item(p, 2)));

        assertThat(count(results, 201)).isEqualTo(10);
        assertThat(results.stream().map(r -> r.body().get("id").asLong()).collect(Collectors.toSet())).hasSize(1);
        assertThat(results.stream().filter(r -> r.replayed() == null).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> "true".equals(r.replayed())).count()).isEqualTo(9);
        assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, user))
                .isEqualTo(1);
        assertThat(api.product(p).get("reserved").asInt()).isEqualTo(2);
        assertThat(api.coupon(coupon).get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-17/07 같은 키에 서로 다른 본문 2종이 동시 도착 -> 주문 1건만 생성, 나머지는 409 idempotency-key-reused")
    void sameKeyDifferentBodiesConcurrentlyYieldOneOrder() throws Exception {
        long p = api.newProduct(1000, 100);
        String user = ApiClient.uniq("u");
        String key = ApiClient.uniq("k");

        List<Result> results = parallel(6, i -> () -> api.placeOrder(user, key, null, item(p, 1 + (i % 2))));

        assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, user))
                .isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() == 201).map(r -> r.body().get("id").asLong())
                .collect(Collectors.toSet())).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() != 201).map(Result::code).collect(Collectors.toSet()))
                .isSubsetOf(java.util.Set.of("IDEMPOTENCY_KEY_REUSED"));
        long reserved = api.product(p).get("reserved").asLong();
        assertThat(reserved).isIn(1L, 2L);
    }

    // ---------------------------------------------------------------- pay

    @Test
    @DisplayName("REQ-17/11 같은 결제 키 6개 동시 요청 -> 모두 200, PG 승인 호출 1회, 재고 1회만 차감")
    void samePayKeyConcurrentlyCallsGatewayOnce() throws Exception {
        long p = api.newProduct(1000, 10);
        long orderId = api.orderOk(p, 2).get("id").asLong();
        String key = ApiClient.uniq("pay");
        PG.onApprove(c -> Reply.delayed(200, Reply.approved("pay-once")));

        List<Result> results = parallel(6, i -> () -> api.pay(orderId, key, "tok_ok"));

        assertThat(count(results, 200)).isEqualTo(6);
        assertThat(results.stream().map(r -> r.body().get("status").asText()).collect(Collectors.toSet()))
                .containsExactly("PAID");
        assertThat(results.stream().filter(r -> r.replayed() == null).count()).isEqualTo(1);
        assertThat(PG.approveCalls()).hasSize(1);
        assertThat(api.product(p).get("stock").asInt()).isEqualTo(8);
        assertThat(api.product(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("REQ-17/10 서로 다른 키로 같은 주문을 동시에 결제 -> 1건만 200 PAID, 나머지 409 invalid-order-state, PG 1회")
    void differentPayKeysOnSameOrderChargeOnce() throws Exception {
        long p = api.newProduct(1000, 10);
        long orderId = api.orderOk(p, 2).get("id").asLong();
        PG.onApprove(c -> Reply.delayed(150, Reply.approved("pay-single")));

        List<Result> results = parallel(6, i -> () -> api.pay(orderId, ApiClient.uniq("pay"), "tok_ok"));

        assertThat(count(results, 200)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(5);
        assertThat(results.stream().filter(r -> r.status() == 409).map(Result::code).collect(Collectors.toSet()))
                .containsExactly("INVALID_ORDER_STATE");
        assertThat(PG.approveCalls()).hasSize(1);
        assertThat(api.product(p).get("stock").asInt()).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- pay vs cancel

    @Test
    @DisplayName("REQ-17/12 pay 와 cancel 경합 (12회 반복) -> 최종 상태는 REFUNDED 또는 CANCELLED 중 하나이며 재고/예약/PG 호출이 항상 일관")
    void payAndCancelRaceEndsInConsistentState() throws Exception {
        int refunded = 0;
        int cancelled = 0;
        for (int round = 0; round < 12; round++) {
            PG.reset();
            PG.onApprove(c -> Reply.delayed(80, Reply.approved("pay-race")));
            long p = api.newProduct(1000, 10);
            long orderId = api.orderOk(p, 2).get("id").asLong();

            List<Result> results = parallel(2, i -> i == 0
                    ? () -> api.pay(orderId, ApiClient.uniq("pay"), "tok_ok")
                    : () -> api.cancel(orderId));

            Result pay = results.get(0);
            Result cancel = results.get(1);
            String finalStatus = orderStatusInDb(orderId);
            JsonNode product = api.product(p);
            assertThat(product.get("stock").asInt()).as("round %d stock", round).isEqualTo(10);
            assertThat(product.get("reserved").asInt()).as("round %d reserved", round).isZero();
            if (finalStatus.equals("REFUNDED")) {
                refunded++;
                assertThat(pay.status()).isEqualTo(200);
                assertThat(cancel.status()).isEqualTo(200);
                assertThat(PG.approveCalls()).hasSize(1);
                assertThat(PG.refundCalls()).hasSize(1);
            } else {
                cancelled++;
                assertThat(finalStatus).isEqualTo("CANCELLED");
                assertThat(cancel.status()).isEqualTo(200);
                assertThat(pay.status()).isEqualTo(409);
                assertThat(pay.code()).isEqualTo("INVALID_ORDER_STATE");
                assertThat(PG.approveCalls()).isEmpty();
                assertThat(PG.refundCalls()).isEmpty();
            }
        }
        assertThat(refunded + cancelled).isEqualTo(12);
    }

    @Test
    @DisplayName("REQ-17/12 PENDING 주문에 cancel 6개 동시 요청 -> 1건 200, 5건 409, 예약/쿠폰은 정확히 1회만 복구")
    void concurrentCancelsReleaseOnce() throws Exception {
        long p = api.newProduct(1000, 10);
        String coupon = api.newCoupon("FIXED", 100, null, null, 5);
        api.orderOk(ApiClient.uniq("other"), p, 3, coupon);
        long orderId = api.orderOk(ApiClient.uniq("u"), p, 2, coupon).get("id").asLong();

        List<Result> results = parallel(6, i -> () -> api.cancel(orderId));

        assertThat(count(results, 200)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(5);
        assertThat(api.product(p).get("reserved").asInt()).isEqualTo(3);
        assertThat(api.coupon(coupon).get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-17/12 PAID 주문에 cancel 6개 동시 요청 -> 1건 200 REFUNDED, 5건 409, PG 환불 1회, 재고 1회만 복구")
    void concurrentCancelsOfPaidOrderRefundOnce() throws Exception {
        long p = api.newProduct(1000, 10);
        long orderId = api.orderOk(p, 2).get("id").asLong();
        api.payOk(orderId);
        PG.onRefund(c -> Reply.delayed(150, Reply.refunded("r")));

        List<Result> results = parallel(6, i -> () -> api.cancel(orderId));

        assertThat(count(results, 200)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(5);
        assertThat(PG.refundCalls()).hasSize(1);
        assertThat(api.product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(orderStatusInDb(orderId)).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("REQ-17/14 PAID 주문에 ship 6개 동시 요청 -> 1건만 200")
    void concurrentShipsSucceedOnce() throws Exception {
        long orderId = api.orderInState("PAID");

        List<Result> results = parallel(6, i -> () -> api.ship(orderId));

        assertThat(count(results, 200)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(5);
        assertThat(orderStatusInDb(orderId)).isEqualTo("SHIPPED");
    }

    @Test
    @DisplayName("REQ-17/14 PAID 주문에 cancel 과 ship 경합 -> 정확히 한쪽만 200, 최종 상태 SHIPPED 또는 REFUNDED 와 재고가 일치")
    void cancelAndShipRaceHasSingleWinner() throws Exception {
        for (int round = 0; round < 8; round++) {
            PG.reset();
            long p = api.newProduct(1000, 10);
            long orderId = api.orderOk(p, 2).get("id").asLong();
            api.payOk(orderId);

            List<Result> results = parallel(2, i -> i == 0 ? () -> api.cancel(orderId) : () -> api.ship(orderId));

            assertThat(count(results, 200)).as("round %d", round).isEqualTo(1);
            assertThat(count(results, 409)).as("round %d", round).isEqualTo(1);
            String finalStatus = orderStatusInDb(orderId);
            if (finalStatus.equals("REFUNDED")) {
                assertThat(api.product(p).get("stock").asInt()).isEqualTo(10);
            } else {
                assertThat(finalStatus).isEqualTo("SHIPPED");
                assertThat(api.product(p).get("stock").asInt()).isEqualTo(8);
                assertThat(PG.refundCalls()).isEmpty();
            }
        }
    }
}
