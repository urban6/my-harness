package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R10 동시성")
class R10ConcurrencyTest extends IntegrationTestBase {

    private long product(int stock) {
        return createProduct("상품", 1000, stock).get("id").asLong();
    }

    private static void assertNo5xx(List<ResponseEntity<JsonNode>> results) {
        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode().is5xxServerError())
                .as("5xx 응답이 있으면 안 된다: %s %s", r.getStatusCode(), r.getBody()).isFalse());
    }

    @Test
    @DisplayName("R10.1 available 10 상품에 수량 1 주문 20건 동시 -> 정확히 10건 201, 10건 409, 최종 reserved 10")
    void r10_1_oversellNeverHappens() {
        for (int round = 0; round < 3; round++) {
            long p = product(10);
            List<Supplier<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String user = "r" + round + "-u" + i;
                tasks.add(() -> createOrder(user, key(), orderBody(null, p, 1)));
            }

            List<ResponseEntity<JsonNode>> results = runConcurrently(tasks);

            assertNo5xx(results);
            assertThat(countStatus(results, 201)).as("round %d 201 건수", round).isEqualTo(10);
            assertThat(countStatus(results, 409)).as("round %d 409 건수", round).isEqualTo(10);
            results.stream().filter(r -> r.getStatusCode().value() == 409)
                    .forEach(r -> assertProblem(r, 409, "INSUFFICIENT_STOCK"));
            JsonNode prod = getProduct(p);
            assertThat(prod.get("reserved").asInt()).isEqualTo(10);
            assertThat(prod.get("available").asInt()).isZero();
            assertThat(prod.get("stock").asInt()).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("R10.2 totalQuantity 5 쿠폰을 서로 다른 사용자 15명이 동시에 사용 -> 정확히 5건 201, 10건 409, 최종 usedCount 5")
    void r10_2_couponQuantityNeverExceeded() {
        long p = product(100);
        Map<String, Object> c = couponBody("CONC0002", "FIXED", 100);
        c.put("totalQuantity", 5);
        createCoupon(c);
        List<Supplier<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String user = "user-" + i;
            tasks.add(() -> createOrder(user, key(), orderBody("CONC0002", p, 1)));
        }

        List<ResponseEntity<JsonNode>> results = runConcurrently(tasks);

        assertNo5xx(results);
        assertThat(countStatus(results, 201)).isEqualTo(5);
        assertThat(countStatus(results, 409)).isEqualTo(10);
        results.stream().filter(r -> r.getStatusCode().value() == 409)
                .forEach(r -> assertProblem(r, 409, "COUPON_EXHAUSTED"));
        assertThat(getCoupon("CONC0002").get("usedCount").asInt()).isEqualTo(5);
        assertThat(getProduct(p).get("reserved").asInt()).as("실패한 주문은 예약을 남기지 않는다").isEqualTo(5);
        assertThat(countOrders()).isEqualTo(5);
    }

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 주문 5건을 (키 상이) 동시에 요청 -> 정확히 1건 201")
    void r10_3_oneCouponPerUser() {
        long p = product(100);
        createCoupon("CONC0003", "FIXED", 100);
        List<Supplier<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> createOrder("same-user", key(), orderBody("CONC0003", p, 1)));
        }

        List<ResponseEntity<JsonNode>> results = runConcurrently(tasks);

        assertNo5xx(results);
        assertThat(countStatus(results, 201)).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(4);
        results.stream().filter(r -> r.getStatusCode().value() == 409)
                .forEach(r -> assertProblem(r, 409, "COUPON_NOT_APPLICABLE"));
        assertThat(getCoupon("CONC0003").get("usedCount").asInt()).isEqualTo(1);
        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(1);
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("R10.4 [P,Q] 와 [Q,P] 순서 주문을 섞어 동시 요청해도 5xx 없이 모두 처리되고 reserved 가 정확하다")
    void r10_4_oppositeLockOrderNoDeadlock() {
        for (int round = 0; round < 3; round++) {
            long p = product(1000);
            long q = product(1000);
            List<Supplier<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String fwdUser = "r" + round + "-f" + i;
                String revUser = "r" + round + "-r" + i;
                tasks.add(() -> createOrder(fwdUser, key(), orderBody(null, p, 1, q, 2)));
                tasks.add(() -> createOrder(revUser, key(), orderBody(null, q, 3, p, 5)));
            }

            List<ResponseEntity<JsonNode>> results = runConcurrently(tasks);

            assertNo5xx(results);
            assertThat(countStatus(results, 201)).as("round %d", round).isEqualTo(40);
            assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(20 * 1 + 20 * 5);
            assertThat(getProduct(q).get("reserved").asInt()).isEqualTo(20 * 2 + 20 * 3);
        }
    }

    @Test
    @DisplayName("R10.4 서로 반대 순서 + 재고 경합이 섞여도 5xx 없이 201/409 만 나오고 reserved 가 stock 을 넘지 않는다")
    void r10_4_oppositeLockOrderWithContention() {
        long p = product(15);
        long q = product(15);
        List<Supplier<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String a = "a" + i;
            String b = "b" + i;
            tasks.add(() -> createOrder(a, key(), orderBody(null, p, 1, q, 1)));
            tasks.add(() -> createOrder(b, key(), orderBody(null, q, 1, p, 1)));
        }

        List<ResponseEntity<JsonNode>> results = runConcurrently(tasks);

        assertNo5xx(results);
        assertThat(countStatus(results, 201)).isEqualTo(15);
        assertThat(countStatus(results, 409)).isEqualTo(15);
        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(15);
        assertThat(getProduct(q).get("reserved").asInt()).isEqualTo(15);
    }

    @Test
    @DisplayName("R10.5 같은 주문에 (키 상이) 결제 10건 동시 -> PG 결제 요청은 최대 1번, 성공 응답은 1건")
    void r10_5_singlePaymentPerOrder() {
        long p = product(10);
        long id = orderOk("u1", null, p, 2).get("id").asLong();
        List<Supplier<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> payOrder(id, key(), "tok"));
        }

        List<ResponseEntity<JsonNode>> results = runConcurrently(tasks);

        assertNo5xx(results);
        assertThat(countStatus(results, 200)).isEqualTo(1);
        assertThat(PG.payCallCount()).isLessThanOrEqualTo(1);
        assertThat(PG.distinctPaymentCount()).isEqualTo(1);
        results.stream().filter(r -> r.getStatusCode().value() != 200)
                .forEach(r -> assertProblem(r, 409, "INVALID_STATE"));
        assertThat(statusOf(id)).isEqualTo("PAID");
        JsonNode prod = getProduct(p);
        assertThat(prod.get("stock").asInt()).as("재고는 한 번만 차감").isEqualTo(8);
        assertThat(prod.get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R10.5 PG 가 느릴 때(1초 지연)도 같은 주문 동시 결제는 PG 결제 1번, 성공 1건")
    void r10_5_singlePaymentPerOrderWithSlowPg() {
        long p = product(10);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        PG.delay(1000);
        List<Supplier<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> payOrder(id, key(), "tok"));
        }

        List<ResponseEntity<JsonNode>> results = runConcurrently(tasks);

        assertThat(countStatus(results, 200)).isEqualTo(1);
        assertThat(PG.payCallCount()).isEqualTo(1);
        assertThat(PG.distinctPaymentCount()).isEqualTo(1);
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(9);
    }
}
