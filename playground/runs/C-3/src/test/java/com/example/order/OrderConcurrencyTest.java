package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** R7(동시성): RANDOM_PORT 실제 HTTP로 동시 요청한다. */
@SuppressWarnings({"rawtypes", "unchecked"})
class OrderConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 20;

    private List<HttpStatus> fireConcurrently(Callable<HttpStatus> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpStatus>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("R7: 재고 10 상품에 수량 1 주문 20건 동시 요청 -> 201 x10, 409 x10, 최종 재고 0")
    void r7_concurrentOrders_exactly10Succeed_10Conflict_stockZero() throws Exception {
        long productId = createProduct("한정판", 1000, 10);

        List<HttpStatus> statuses = fireConcurrently(
                () -> HttpStatus.valueOf(placeOrder(item(productId, 1)).getStatusCode().value()));

        assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED).hasSize(10);
        assertThat(statuses).filteredOn(s -> s == HttpStatus.CONFLICT).hasSize(10);
        assertThat(stockOf(productId)).isZero();
        assertThat(orderCount()).isEqualTo(10L);
    }

    @Test
    @DisplayName("R7: 서로 반대 순서로 두 상품을 주문하는 동시 요청에서도 교착 없이 재고 정합성이 유지된다")
    void r7_concurrentMultiItemOrders_oppositeOrder_noDeadlock_consistentStock() throws Exception {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 1000, 10);
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();

        List<HttpStatus> statuses = fireConcurrently(() -> {
            boolean flip = counter.getAndIncrement() % 2 == 0;
            ResponseEntity<Map> res = flip ? placeOrder(item(a, 1), item(b, 1)) : placeOrder(item(b, 1), item(a, 1));
            return HttpStatus.valueOf(res.getStatusCode().value());
        });

        assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED).hasSize(10);
        assertThat(statuses).filteredOn(s -> s == HttpStatus.CONFLICT).hasSize(10);
        assertThat(stockOf(a)).isZero();
        assertThat(stockOf(b)).isZero();
    }
}
