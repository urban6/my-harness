package com.example.order.orders;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.example.order.support.IntegrationTest;

@DisplayName("R7 동시성")
class OrderConcurrencyTest extends IntegrationTest {

    private static final int REQUESTS = 20;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void concurrentOrders_neverOversell() throws Exception {
        long product = createProduct("한정판", 1000, 10);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                "{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(product), headers);

        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(REQUESTS)) {
            for (int i = 0; i < REQUESTS; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return restTemplate.postForEntity("/api/orders", request, String.class);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int created = 0;
            int conflict = 0;
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(60, TimeUnit.SECONDS);
                switch (response.getStatusCode().value()) {
                    case 201 -> created++;
                    case 409 -> {
                        conflict++;
                        assertThat(response.getHeaders().getContentType())
                                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
                    }
                    default -> throw new AssertionError("unexpected response: " + response);
                }
            }

            assertThat(created).isEqualTo(10);
            assertThat(conflict).isEqualTo(10);
        }
        assertThat(stockOf(product)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(10);
    }
}
