package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("R7 동시성")
class OrderConcurrencyTest extends IntegrationTestSupport {

    private static final int STOCK = 10;
    private static final int REQUESTS = 20;

    @RepeatedTest(3)
    void exactlyStockManyConcurrentOrdersSucceed() throws Exception {
        long product = createProduct("Limited", 1000, STOCK);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(REQUESTS)) {
            for (int i = 0; i < REQUESTS; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return createOrder(item(product, 1));
                }));
            }
            ready.await();
            start.countDown();
        }

        int created = 0;
        int conflicts = 0;
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> response = future.get();
            if (response.getStatusCode() == HttpStatus.CREATED) {
                created++;
            } else {
                assertProblem(response, HttpStatus.CONFLICT);
                conflicts++;
            }
        }

        assertThat(created).isEqualTo(STOCK);
        assertThat(conflicts).isEqualTo(REQUESTS - STOCK);
        assertThat(stockOf(product)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(STOCK);
    }
}
