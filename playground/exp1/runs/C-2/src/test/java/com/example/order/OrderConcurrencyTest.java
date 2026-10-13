package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * R7 동시성. 테스트 메서드에 @Transactional 을 붙이지 않는다(요청마다 독립 트랜잭션).
 */
class OrderConcurrencyTest extends AbstractApiTest {

    /** 모든 작업을 latch 로 동시에 출발시켜 결과를 수집한다. */
    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("R7 재고 10 상품에 수량 1 주문 20건 동시 요청 -> 201 정확히 10, 409 정확히 10, 최종 재고 0, 주문 10건")
    void stock10_20concurrentOrders_exactly10Created_10Conflict() throws Exception {
        long p = createProduct("한정판", 1000, 10);

        List<ResponseEntity<JsonNode>> results = runConcurrently(20, () -> createOrder(List.of(item(p, 1))));

        long created = results.stream().filter(r -> r.getStatusCode().value() == 201).count();
        long conflict = results.stream().filter(r -> r.getStatusCode().value() == 409).count();
        assertThat(created).isEqualTo(10);
        assertThat(conflict).isEqualTo(10);
        assertThat(stockOf(p)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM order_items", Integer.class))
                .isEqualTo(10);
    }

    @Test
    @DisplayName("R7+R8 동시 경합으로 실패한 10건은 모두 insufficient-stock problem+json 이다")
    void concurrentConflicts_areInsufficientStockProblems() throws Exception {
        long p = createProduct("한정판", 1000, 10);

        List<ResponseEntity<JsonNode>> results = runConcurrently(20, () -> createOrder(List.of(item(p, 1))));

        List<ResponseEntity<JsonNode>> conflicts =
                results.stream().filter(r -> r.getStatusCode().value() == 409).toList();
        assertThat(conflicts).hasSize(10);
        conflicts.forEach(r -> assertProblem(r, 409, "insufficient-stock"));
    }

    @Test
    @DisplayName("R7 다항목 주문 경합에서도 부분 차감이 없다(두 상품 재고가 항상 같은 수만큼 줄어든다)")
    void concurrentMultiItemOrders_areAtomic() throws Exception {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 1000, 6); // b 가 먼저 소진된다

        List<ResponseEntity<JsonNode>> results =
                runConcurrently(20, () -> createOrder(List.of(item(a, 1), item(b, 1))));

        long created = results.stream().filter(r -> r.getStatusCode().value() == 201).count();
        long conflict = results.stream().filter(r -> r.getStatusCode().value() == 409).count();
        assertThat(created).isEqualTo(6);
        assertThat(conflict).isEqualTo(14);
        assertThat(stockOf(a)).isEqualTo(4);
        assertThat(stockOf(b)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(6);
    }

    @Test
    @DisplayName("R5+R7 같은 주문을 동시에 5번 취소하면 200 은 정확히 1번, 나머지 409, 재고는 한 번만 복원")
    void concurrentCancel_restoresStockOnce() throws Exception {
        long p = createProduct("A", 1000, 10);
        long orderId = createOrder(List.of(item(p, 4))).getBody().get("id").asLong();
        assertThat(stockOf(p)).isEqualTo(6);

        List<ResponseEntity<JsonNode>> results =
                runConcurrently(5, () -> postNoBody("/api/orders/" + orderId + "/cancel"));

        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 200).count()).isEqualTo(1);
        List<ResponseEntity<JsonNode>> conflicts =
                results.stream().filter(r -> r.getStatusCode().value() == 409).toList();
        assertThat(conflicts).hasSize(4);
        conflicts.forEach(r -> assertProblem(r, 409, "order-already-cancelled"));
        assertThat(stockOf(p)).isEqualTo(10);
    }
}
