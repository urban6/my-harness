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

import com.example.order.support.AbstractIntegrationTest;

/** R7 동시성: 실제 HTTP 병렬 요청. (테스트 메서드에 @Transactional 없음) */
class OrderConcurrencyTest extends AbstractIntegrationTest {

    private static final int REQUESTS = 20;
    private static final int STOCK = 10;

    @Test
    @DisplayName("R7: 재고 10 상품에 수량 1 주문 20건을 동시에 요청하면 201 정확히 10건, 409 10건, 최종 재고 0")
    void r7_concurrentOrders_exactlyStockSucceed() throws Exception {
        long productId = createProduct("한정판", 10000, STOCK);
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < REQUESTS; i++) {
                Callable<HttpStatus> task = () -> {
                    ready.countDown();
                    start.await();
                    ResponseEntity<Map> res = postOrder(item(productId, 1));
                    return HttpStatus.valueOf(res.getStatusCode().value());
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses.stream().filter(s -> s == HttpStatus.CREATED).count()).isEqualTo(STOCK);
            assertThat(statuses.stream().filter(s -> s == HttpStatus.CONFLICT).count())
                    .isEqualTo(REQUESTS - STOCK);
            assertThat(stockOf(productId)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("R7: 동시 주문 후 성공한 주문 수는 재고 감소량과 일치한다(주문 행 수 = 10)")
    void r7_concurrentOrders_createdOrderCountMatchesSuccesses() throws Exception {
        long productId = createProduct("한정판2", 10000, STOCK);
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < REQUESTS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return postOrder(item(productId, 1)).getStatusCode().value();
                }));
            }
            start.countDown();
            for (Future<Integer> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }

            Integer orderRows = jdbcTemplate.queryForObject(
                    "select count(*) from order_items where product_id = ?", Integer.class, productId);
            assertThat(orderRows).isEqualTo(STOCK);
        } finally {
            pool.shutdownNow();
        }
    }
}
