package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

class OrderCreationTest extends IntegrationTestBase {

    @Test
    void reservesStockAndReturnsPendingOrder() throws Exception {
        long productId = createProduct(1000, 10);

        placeOrder("u1", "k-" + UUID.randomUUID(), orderBody(null, productId, 3))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/orders/")))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.userId").value("u1"))
                .andExpect(jsonPath("$.items[0].productId").value(productId))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.subtotal").value(3000))
                .andExpect(jsonPath("$.discount").value(0))
                .andExpect(jsonPath("$.totalPrice").value(3000))
                .andExpect(jsonPath("$.couponCode").doesNotExist())
                .andExpect(jsonPath("$.paidAt").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").exists());

        JsonNode p = product(productId);
        assertThat(p.get("stock").asLong()).isEqualTo(10);
        assertThat(p.get("reserved").asLong()).isEqualTo(3);
        assertThat(p.get("available").asLong()).isEqualTo(7);
    }

    @Test
    void expiresAtIsCreatedAtPlusTtl() throws Exception {
        long productId = createProduct(100, 5);
        JsonNode o = read(placeOrder("u1", null, orderBody(null, productId, 1)));
        Duration ttl = Duration.between(Instant.parse(o.get("createdAt").asText()),
                Instant.parse(o.get("expiresAt").asText()));
        assertThat(ttl).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void getReturnsTheCreatedOrder() throws Exception {
        long productId = createProduct(100, 5);
        long id = order("u1", productId, 2);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders/999999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateProductLinesAreMerged() throws Exception {
        long productId = createProduct(100, 10);
        placeOrder("u1", null, orderBody(null, productId, 2, productId, 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5));
        assertThat(product(productId).get("reserved").asLong()).isEqualTo(5);
    }

    @Test
    void insufficientStockIsConflictAndLeavesNothingReserved() throws Exception {
        long plenty = createProduct(100, 10);
        long scarce = createProduct(100, 1);

        placeOrder("u1", null, orderBody(null, plenty, 2, scarce, 2))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(product(plenty).get("reserved").asLong()).isZero();
        assertThat(product(scarce).get("reserved").asLong()).isZero();
    }

    @Test
    void validatesRequest() throws Exception {
        long productId = createProduct(100, 10);
        placeOrder("u1", null, orderBody(null)).andExpect(status().isBadRequest());
        placeOrder("u1", null, orderBody(null, productId, 0)).andExpect(status().isBadRequest());
        placeOrder("u1", null, orderBody(null, 999999999L, 1)).andExpect(status().isNotFound());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(orderBody(null, productId, 1))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---------------------------------------------------------------- 쿠폰

    @Test
    void fixedCouponDiscountIsCappedAtSubtotal() throws Exception {
        long productId = createProduct(1000, 10);
        String code = createCoupon("FIXED", 700, 0, null, 10);

        placeOrder("u1", null, orderBody(code, productId, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.couponCode").value(code))
                .andExpect(jsonPath("$.subtotal").value(1000))
                .andExpect(jsonPath("$.discount").value(700))
                .andExpect(jsonPath("$.totalPrice").value(300));
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);

        String big = createCoupon("FIXED", 99999, 0, null, 10);
        placeOrder("u1", null, orderBody(big, productId, 1))
                .andExpect(jsonPath("$.discount").value(1000))
                .andExpect(jsonPath("$.totalPrice").value(0));
    }

    @Test
    void rateCouponRespectsMaxDiscount() throws Exception {
        long productId = createProduct(1000, 10);
        String uncapped = createCoupon("RATE", 15, 0, null, 10);
        String capped = createCoupon("RATE", 50, 0, 600L, 10);

        placeOrder("u1", null, orderBody(uncapped, productId, 2))
                .andExpect(jsonPath("$.discount").value(300))
                .andExpect(jsonPath("$.totalPrice").value(1700));
        placeOrder("u1", null, orderBody(capped, productId, 2))
                .andExpect(jsonPath("$.discount").value(600))
                .andExpect(jsonPath("$.totalPrice").value(1400));
    }

    @Test
    void couponRulesAreEnforcedAndFailedOrdersLeaveNoTrace() throws Exception {
        long productId = createProduct(1000, 10);

        String minimum = createCoupon("FIXED", 100, 5000, null, 10);
        placeOrder("u1", null, orderBody(minimum, productId, 1)).andExpect(status().isUnprocessableEntity());

        postJson("/api/coupons", Map.of("code", "OLD-" + UUID.randomUUID(), "type", "FIXED", "value", 100,
                "minOrderAmount", 0, "totalQuantity", 5,
                "validFrom", "2020-01-01T00:00:00Z", "validUntil", "2020-02-01T00:00:00Z"));
        String expired = "EXP-" + UUID.randomUUID();
        postJson("/api/coupons", Map.of("code", expired, "type", "FIXED", "value", 100, "minOrderAmount", 0,
                "totalQuantity", 5, "validFrom", "2020-01-01T00:00:00Z", "validUntil", "2020-02-01T00:00:00Z"));
        placeOrder("u1", null, orderBody(expired, productId, 1)).andExpect(status().isUnprocessableEntity());

        placeOrder("u1", null, orderBody("NOPE-" + UUID.randomUUID(), productId, 1))
                .andExpect(status().isNotFound());

        String single = createCoupon("FIXED", 100, 0, null, 1);
        placeOrder("u1", null, orderBody(single, productId, 1)).andExpect(status().isCreated());
        placeOrder("u2", null, orderBody(single, productId, 1)).andExpect(status().isConflict());

        // 위 실패한 주문들은 모두 재고 예약을 되돌렸어야 한다 (성공한 주문 1건 = 1개만 예약).
        assertThat(product(productId).get("reserved").asLong()).isEqualTo(1);
        assertThat(coupon(single).get("usedCount").asLong()).isEqualTo(1);
        assertThat(coupon(minimum).get("usedCount").asLong()).isZero();
    }

    // ---------------------------------------------------------------- 멱등성

    @Test
    void sameIdempotencyKeyReturnsTheSameOrderWithoutReservingTwice() throws Exception {
        long productId = createProduct(100, 10);
        String key = "idem-" + UUID.randomUUID();

        JsonNode first = read(placeOrder("u1", key, orderBody(null, productId, 2)));
        placeOrder("u1", key, orderBody(null, productId, 2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(first.get("id").asLong()));

        assertThat(product(productId).get("reserved").asLong()).isEqualTo(2);

        placeOrder("u1", key, orderBody(null, productId, 3)).andExpect(status().isUnprocessableEntity());
        // 키는 사용자별로 구분된다.
        JsonNode other = read(placeOrder("u2", key, orderBody(null, productId, 2)));
        assertThat(other.get("id").asLong()).isNotEqualTo(first.get("id").asLong());
    }

    // ---------------------------------------------------------------- 동시성

    @Test
    void concurrentOrdersNeverOversellStock() throws Exception {
        long productId = createProduct(100, 5);

        List<Integer> statuses = runConcurrently(20, i ->
                placeOrder("u" + i, null, orderBody(null, productId, 1)).andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(15);
        JsonNode p = product(productId);
        assertThat(p.get("reserved").asLong()).isEqualTo(5);
        assertThat(p.get("available").asLong()).isZero();
    }

    @Test
    void concurrentOrdersNeverExceedCouponQuantity() throws Exception {
        long productId = createProduct(100, 100);
        String code = createCoupon("FIXED", 10, 0, null, 3);

        List<Integer> statuses = runConcurrently(12, i ->
                placeOrder("u" + i, null, orderBody(code, productId, 1)).andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(3);
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(3);
        // 쿠폰 때문에 실패한 주문의 재고 예약은 롤백되어야 한다.
        assertThat(product(productId).get("reserved").asLong()).isEqualTo(3);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneOrder() throws Exception {
        long productId = createProduct(100, 100);
        String key = "same-" + UUID.randomUUID();

        List<Integer> ids = runConcurrently(8, i -> {
            var res = placeOrder("u1", key, orderBody(null, productId, 2)).andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(201);
            return json.readTree(res.getContentAsString()).get("id").asInt();
        });

        Set<Integer> distinct = new HashSet<>(ids);
        assertThat(distinct).hasSize(1);
        assertThat(product(productId).get("reserved").asLong()).isEqualTo(2);
    }

    private <T> List<T> runConcurrently(int n, ThrowingFunction<Integer, T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.apply(idx);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    interface ThrowingFunction<A, R> {
        R apply(A a) throws Exception;
    }
}
