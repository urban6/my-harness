package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OrderCreationTest extends ApiTestSupport {

    @Test
    void createsOrderAndReservesStock() throws Exception {
        long p1 = createProduct(10000, 10);
        long p2 = createProduct(2500, 4);
        String user = "u-" + uid();

        placeOrder(user, "k-" + uid(), null, p1, 2, p2, 3)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(".*/api/orders/\\d+")))
                .andExpect(jsonPath("$.userId").value(user))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items[0].productId").value(p1))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(10000))
                .andExpect(jsonPath("$.subtotal").value(27500))
                .andExpect(jsonPath("$.discount").value(0))
                .andExpect(jsonPath("$.totalPrice").value(27500))
                .andExpect(jsonPath("$.couponCode").doesNotExist())
                .andExpect(jsonPath("$.paidAt").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").exists());

        assertThat(product(p1).get("reserved").asInt()).isEqualTo(2);
        assertThat(product(p1).get("available").asInt()).isEqualTo(8);
        assertThat(product(p2).get("reserved").asInt()).isEqualTo(3);
        assertThat(product(p2).get("stock").asInt()).isEqualTo(4);
    }

    @Test
    void mergesDuplicateLines() throws Exception {
        long p = createProduct(1000, 10);
        JsonNode order = newOrder("u-" + uid(), null, p, 2, p, 3);
        assertThat(order.get("items")).hasSize(1);
        assertThat(order.get("items").get(0).get("quantity").asInt()).isEqualTo(5);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(5);
    }

    @Test
    void validatesRequest() throws Exception {
        long p = createProduct(1000, 10);
        String user = "u-" + uid();
        // 헤더 누락
        postJson("/api/orders", orderBody(null, p, 1), "Idempotency-Key", "k-" + uid())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        postJson("/api/orders", orderBody(null, p, 1), "X-User-Id", user)
                .andExpect(status().isBadRequest());
        // 빈 항목 / 수량 0
        placeOrder(user, "k-" + uid(), null).andExpect(status().isBadRequest());
        placeOrder(user, "k-" + uid(), null, p, 0).andExpect(status().isBadRequest());
        // 없는 상품
        placeOrder(user, "k-" + uid(), null, 987654321L, 1).andExpect(status().isNotFound());
        mvcGetMissingOrder();
    }

    private void mvcGetMissingOrder() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders/987654321"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void insufficientStockIsConflictAndReservesNothing() throws Exception {
        long plenty = createProduct(1000, 10);
        long scarce = createProduct(1000, 1);
        placeOrder("u-" + uid(), "k-" + uid(), null, plenty, 5, scarce, 2)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(product(plenty).get("reserved").asInt()).isZero();
        assertThat(product(scarce).get("reserved").asInt()).isZero();
    }

    @Test
    void fixedCouponAppliesAndIsCountedOnce() throws Exception {
        long p = createProduct(10000, 10);
        String code = createCoupon("FIXED", 3000, 10000, null, 5);
        JsonNode order = newOrder("u-" + uid(), code, p, 2);
        assertThat(order.get("couponCode").asText()).isEqualTo(code);
        assertThat(order.get("subtotal").asLong()).isEqualTo(20000);
        assertThat(order.get("discount").asLong()).isEqualTo(3000);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(17000);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    void rateCouponIsCappedByMaxDiscount() throws Exception {
        long p = createProduct(10000, 10);
        String capped = createCoupon("RATE", 20, 0, 3000L, 5);
        JsonNode a = newOrder("u-" + uid(), capped, p, 2);   // 20% of 20000 = 4000 -> cap 3000
        assertThat(a.get("discount").asLong()).isEqualTo(3000);
        assertThat(a.get("totalPrice").asLong()).isEqualTo(17000);

        String uncapped = createCoupon("RATE", 15, 0, null, 5);
        JsonNode b = newOrder("u-" + uid(), uncapped, p, 1);  // 15% of 10000 = 1500
        assertThat(b.get("discount").asLong()).isEqualTo(1500);
    }

    @Test
    void fixedCouponNeverExceedsSubtotal() throws Exception {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 5000, 0, null, 5);
        JsonNode order = newOrder("u-" + uid(), code, p, 1);
        assertThat(order.get("discount").asLong()).isEqualTo(1000);
        assertThat(order.get("totalPrice").asLong()).isZero();
    }

    @Test
    void couponRulesAreEnforced() throws Exception {
        long p = createProduct(10000, 10);
        String user = "u-" + uid();

        String minOrder = createCoupon("FIXED", 1000, 50000, null, 5);
        placeOrder(user, "k-" + uid(), minOrder, p, 1).andExpect(status().isUnprocessableEntity());

        String expired = createCoupon("FIXED", 1000, 0, null, 5,
                Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS));
        placeOrder(user, "k-" + uid(), expired, p, 1).andExpect(status().isUnprocessableEntity());

        String future = createCoupon("FIXED", 1000, 0, null, 5,
                Instant.now().plus(1, ChronoUnit.DAYS), Instant.now().plus(10, ChronoUnit.DAYS));
        placeOrder(user, "k-" + uid(), future, p, 1).andExpect(status().isUnprocessableEntity());

        placeOrder(user, "k-" + uid(), "NOPE" + uid(), p, 1).andExpect(status().isNotFound());

        String single = createCoupon("FIXED", 1000, 0, null, 1);
        newOrder(user, single, p, 1);
        placeOrder(user, "k-" + uid(), single, p, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_EXHAUSTED"));

        // 실패한 주문은 재고를 남기지 않는다: 위에서 성공한 주문 1건(1개)만 예약
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);
        assertThat(coupon(minOrder).get("usedCount").asInt()).isZero();
    }

    @Test
    void sameIdempotencyKeyReplaysTheSameOrder() throws Exception {
        long p = createProduct(1000, 10);
        String user = "u-" + uid();
        String key = "k-" + uid();

        JsonNode first = body(placeOrder(user, key, null, p, 2).andExpect(status().isCreated()));
        JsonNode second = body(placeOrder(user, key, null, p, 2).andExpect(status().isCreated()));

        assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    void reusingKeyWithDifferentBodyIsRejected() throws Exception {
        long p = createProduct(1000, 10);
        String user = "u-" + uid();
        String key = "k-" + uid();
        placeOrder(user, key, null, p, 2).andExpect(status().isCreated());
        placeOrder(user, key, null, p, 3)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);

        // 같은 키라도 다른 사용자는 별개
        placeOrder("u-" + uid(), key, null, p, 3).andExpect(status().isCreated());
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneOrder() throws Exception {
        long p = createProduct(1000, 1);   // 재고 1: 중복 생성되면 두 번째가 409가 된다
        String user = "u-" + uid();
        String key = "k-" + uid();

        List<Callable<Integer>> tasks = new ArrayList<>();
        List<Long> ids = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> {
                var res = placeOrder(user, key, null, p, 1).andReturn().getResponse();
                if (res.getStatus() == 201) {
                    ids.add(json.readTree(res.getContentAsString()).get("id").asLong());
                }
                return res.getStatus();
            });
        }
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses).allMatch(s -> s == 201);
        assertThat(ids.stream().distinct()).hasSize(1);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentOrdersNeverOversellStock() throws Exception {
        long p = createProduct(1000, 3);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String user = "u-" + uid();
            tasks.add(() -> placeOrder(user, "k-" + uid(), null, p, 1).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(3);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(9);
        JsonNode product = product(p);
        assertThat(product.get("reserved").asInt()).isEqualTo(3);
        assertThat(product.get("available").asInt()).isZero();
    }

    @Test
    void concurrentOrdersNeverOveruseCoupon() throws Exception {
        long p = createProduct(1000, 100);
        String code = createCoupon("FIXED", 100, 0, null, 2);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String user = "u-" + uid();
            tasks.add(() -> placeOrder(user, "k-" + uid(), code, p, 1).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(2);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(8);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(2);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);   // 쿠폰 실패 주문의 재고는 롤백
    }

    @Test
    void concurrentMultiProductOrdersDoNotDeadlock() throws Exception {
        long a = createProduct(1000, 100);
        long b = createProduct(1000, 100);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String user = "u-" + uid();
            boolean swap = i % 2 == 0;
            tasks.add(() -> (swap ? placeOrder(user, "k-" + uid(), null, a, 1, b, 1)
                    : placeOrder(user, "k-" + uid(), null, b, 1, a, 1)).andReturn().getResponse().getStatus());
        }
        assertThat(runConcurrently(tasks)).allMatch(s -> s == 201);
        assertThat(product(a).get("reserved").asInt()).isEqualTo(20);
        assertThat(product(b).get("reserved").asInt()).isEqualTo(20);
    }

    static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
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
            pool.shutdownNow();
        }
    }
}
