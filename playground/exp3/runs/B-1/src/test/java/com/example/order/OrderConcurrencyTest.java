package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class OrderConcurrencyTest extends IntegrationTest {

    @Test
    void concurrentOrders_neverOversellStock() throws Exception {
        long p = createProduct("A", 1000, 5);

        List<Integer> statuses = runConcurrently(12, i -> placeOrder(i + 1, "k-" + i, null, p, 1)
                .andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(7);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(5);
        assertThat(product(p).get("available").asInt()).isZero();
    }

    @Test
    void concurrentOrders_neverExceedCouponQuantity() throws Exception {
        long p = createProduct("A", 1000, 100);
        String code = createCoupon("FIXED", 100, 0, null, 3);

        List<Integer> statuses = runConcurrently(10, i -> placeOrder(i + 1, "kc-" + i, code, p, 1)
                .andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(7);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(3);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(3);
    }

    @Test
    void concurrentSameIdempotencyKey_createsExactlyOneOrder() throws Exception {
        long p = createProduct("A", 1000, 10);

        List<Long> ids = runConcurrently(8, i -> json(placeOrder(1, "k-race", null, p, 1)
                .andExpect(status().isCreated())).get("id").asLong());

        assertThat(ids.stream().distinct().count()).isEqualTo(1);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentPayments_withDifferentKeys_chargeOnlyOnce() throws Exception {
        long p = createProduct("A", 1000, 10);
        long id = order(1, null, p, 1).get("id").asLong();

        List<Integer> statuses = runConcurrently(6, i -> pay(id, "pk-" + i, "tok_slow")
                .andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(5);
        assertThat(GATEWAY.chargeCalls.get()).isEqualTo(1);
        assertThat(getOrder(id).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asInt()).isEqualTo(9);
    }

    @Test
    void concurrentCancelAndPay_endInConsistentState() throws Exception {
        long p = createProduct("A", 1000, 10);
        long id = order(1, null, p, 2).get("id").asLong();

        List<Callable<Integer>> tasks = List.of(
                () -> pay(id, "pk", "tok_slow").andReturn().getResponse().getStatus(),
                () -> action(id, "cancel").andReturn().getResponse().getStatus());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        JsonNode o = getOrder(id);
        JsonNode prod = product(p);
        switch (o.get("status").asText()) {
            case "PAID" -> {
                assertThat(prod.get("stock").asInt()).isEqualTo(8);
                assertThat(prod.get("reserved").asInt()).isZero();
            }
            case "CANCELLED" -> {
                assertThat(prod.get("stock").asInt()).isEqualTo(10);
                assertThat(prod.get("reserved").asInt()).isZero();
                assertThat(GATEWAY.chargeCalls.get()).isZero();
            }
            case "REFUNDED" -> {
                assertThat(prod.get("stock").asInt()).isEqualTo(10);
                assertThat(prod.get("reserved").asInt()).isZero();
            }
            default -> throw new AssertionError("예상 밖 상태: " + o.get("status"));
        }
    }

    private interface Task<T> {
        T run(int index) throws Exception;
    }

    private <T> List<T> runConcurrently(int n, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.run(idx);
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }
}
