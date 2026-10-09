package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

@DisplayName("R7 동시성")
class OrderConcurrencyTest extends ApiTestSupport {

    @Test
    @DisplayName("재고 10개 상품에 수량 1 주문 20건 동시 요청 → 201 10건, 409 10건, 최종 재고 0")
    void onlyStockAmountOfConcurrentOrdersSucceed() throws Exception {
        long productId = createProduct("한정판", 1000, 10);
        int requests = 20;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return placeOrder(items(productId, 1));
                }));
            }
            ready.await();
            start.countDown();
        }

        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            responses.add(future.get());
        }
        Map<HttpStatusCode, Long> statusCounts = responses.stream()
                .collect(Collectors.groupingBy(ResponseEntity::getStatusCode, Collectors.counting()));

        assertThat(statusCounts).containsOnly(
                Map.entry(HttpStatus.CREATED, 10L),
                Map.entry(HttpStatus.CONFLICT, 10L));
        responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(r -> assertProblem(r, HttpStatus.CONFLICT));
        assertThat(stockOf(productId)).isZero();
        assertThat(countOrders()).isEqualTo(10);
        assertThat(json(get("/api/products/" + productId)).get("stock").asInt()).isZero();
    }
}
