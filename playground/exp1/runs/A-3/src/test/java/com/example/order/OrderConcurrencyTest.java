package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R7 동시성")
class OrderConcurrencyTest extends IntegrationTestSupport {

    @Test
    @DisplayName("재고 10개 상품에 수량 1 주문 20건을 동시에 요청하면 10건 201, 10건 409, 최종 재고 0")
    void concurrentOrdersNeverOversell() throws Exception {
        long product = createProduct("Limited", 1000, 10);
        int requests = 20;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return createOrder(product, 1).getStatusCode().value();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses).filteredOn(status -> status == 201).hasSize(10);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(10);
            assertThat(stockOf(product)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("동시에 같은 주문을 여러 번 취소해도 한 번만 성공하고 재고는 한 번만 복원된다")
    void concurrentCancelRestoresStockOnce() throws Exception {
        long product = createProduct("Limited", 1000, 10);
        long orderId = createOrderId(product, 4);
        int requests = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return post("/api/orders/" + orderId + "/cancel").getStatusCode().value();
                }));
            }
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(requests - 1);
            assertThat(stockOf(product)).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }
    }
}
