package com.example.order;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.order.payment.PaymentGateway.ChargeResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class OrderConcurrencyTest extends IntegrationTest {

    private static final int THREADS = 20;

    @Test
    void concurrentOrders_neverOversellStock() throws Exception {
        long product = createProduct(1000, 5);

        List<Integer> statuses = runConcurrently(i -> placeOrder("u" + i, unique(), items(product, 1), null)
                .andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(THREADS - 5);
        assertThat(getProduct(product).get("reserved").asInt()).isEqualTo(5);
    }

    @Test
    void concurrentOrders_neverExceedCouponQuantity() throws Exception {
        long product = createProduct(1000, 100);
        String coupon = createCoupon("FIXED", 100, null, null, 3);

        List<Integer> statuses = runConcurrently(i -> placeOrder("u" + i, unique(), items(product, 1), coupon)
                .andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(3);
        mvc.perform(get("/api/coupons/" + coupon)).andExpect(jsonPath("$.usedCount").value(3));
        assertThat(getProduct(product).get("reserved").asInt()).isEqualTo(3);   // 실패한 주문의 예약은 롤백
    }

    @Test
    void concurrentOrders_withSameIdempotencyKey_createOneOrder() throws Exception {
        long product = createProduct(1000, 100);
        String key = unique();

        List<Integer> statuses = runConcurrently(i -> placeOrder("same-user", key, items(product, 1), null)
                .andReturn().getResponse().getStatus());

        assertThat(statuses).allMatch(s -> s == 201);
        assertThat(getProduct(product).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentPaymentsOnSameOrder_chargeOnce() throws Exception {
        long product = createProduct(1000, 10);
        long order = json(placeOrder("u1", unique(), items(product, 1), null)).get("id").asLong();
        given(gateway.charge(anyLong(), anyLong(), anyString(), anyString())).willReturn(new ChargeResult("pg-c", true));

        List<Integer> statuses = runConcurrently(i -> mvc.perform(post("/api/orders/" + order + "/pay")
                        .header("Idempotency-Key", "key-" + i)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardToken\":\"tok\"}"))
                .andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        verify(gateway, times(1)).charge(anyLong(), anyLong(), anyString(), anyString());
        JsonNodeAssert.status(getOrder(order), "PAID");
        assertThat(getProduct(product).get("stock").asInt()).isEqualTo(9);
    }

    private com.fasterxml.jackson.databind.JsonNode getOrder(long id) throws Exception {
        return json(mvc.perform(get("/api/orders/" + id)));
    }

    private interface Call { int run(int index) throws Exception; }

    private List<Integer> runConcurrently(Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            int index = counter.getAndIncrement();
            futures.add(pool.submit(() -> {
                start.await();
                return call.run(index);
            }));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    private static final class JsonNodeAssert {
        static void status(com.fasterxml.jackson.databind.JsonNode order, String expected) {
            assertThat(order.get("status").asText()).isEqualTo(expected);
        }
    }
}
