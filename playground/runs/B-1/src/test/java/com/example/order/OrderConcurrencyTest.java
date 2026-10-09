package com.example.order;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.order.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R7 동시성")
class OrderConcurrencyTest extends ApiTestSupport {

    @Test
    void concurrentOrders_neverOversell() throws Exception {
        long p = createProduct("한정판", 1000, 10);
        int requests = 20;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatusCode>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return createOrder(List.of(item(p, 1))).getStatusCode();
                }));
            }
            ready.await();
            start.countDown();

            long created = 0;
            long conflict = 0;
            for (Future<HttpStatusCode> result : results) {
                HttpStatusCode status = result.get();
                if (status.equals(HttpStatus.CREATED)) {
                    created++;
                } else if (status.equals(HttpStatus.CONFLICT)) {
                    conflict++;
                }
            }

            assertThat(created).isEqualTo(10);
            assertThat(conflict).isEqualTo(10);
        }
        assertThat(stockOf(p)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isEqualTo(10);
    }

    @Test
    void concurrentCancels_restoreStockOnlyOnce() throws Exception {
        long p = createProduct("a", 1000, 5);
        long id = createOrder(List.of(item(p, 5))).getBody().get("id").asLong();
        int requests = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatusCode>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return post("/api/orders/" + id + "/cancel", null).getStatusCode();
                }));
            }
            start.countDown();

            long ok = 0;
            for (Future<HttpStatusCode> result : results) {
                if (result.get().equals(HttpStatus.OK)) {
                    ok++;
                } else {
                    assertThat(result.get()).isEqualTo(HttpStatus.CONFLICT);
                }
            }
            assertThat(ok).isEqualTo(1);
        }
        assertThat(stockOf(p)).isEqualTo(5);
    }
}
