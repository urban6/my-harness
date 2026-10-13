package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class OrderConcurrencyTest extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("R7 동시성: 재고 10에 수량 1 주문 20건 동시 요청 → 201 10건, 409 10건, 최종 재고 0")
    void concurrentOrders_neverOversell() throws Exception {
        long product = createProduct("한정판", 1000, 10);
        int requests = 20;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(orderBody(product, 1), headers);

        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return rest.postForEntity("/api/orders", request, String.class).getStatusCode().value();
                }));
            }
            ready.await();
            start.countDown();
        }

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        Map<Integer, Long> counts = statuses.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(counts).containsOnly(Map.entry(201, 10L), Map.entry(409, 10L));
        assertThat(stockOf(product)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(10);
    }
}
