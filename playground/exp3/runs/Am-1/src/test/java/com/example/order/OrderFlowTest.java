package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OrderFlowTest extends AbstractIntegrationTest {

    @Test
    void productAndCouponCrud() throws Exception {
        postJson("/api/products", Map.of("name", "pen", "price", 1000, "stock", 5))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.available").value(5));
        postJson("/api/products", Map.of("name", "", "price", -1, "stock", 5))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        mvc.perform(get("/api/products/999999")).andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));

        String code = createCoupon("RATE", 10, 0, 500L, 3);
        mvc.perform(get("/api/coupons/" + code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.usedCount").value(0)).andExpect(jsonPath("$.type").value("RATE"));
        postJson("/api/coupons", Map.of("code", code, "type", "FIXED", "value", 1, "totalQuantity", 1,
                "validFrom", "2020-01-01T00:00:00Z", "validUntil", "2030-01-01T00:00:00Z"))
                .andExpect(status().isConflict());
        postJson("/api/coupons", Map.of("code", "BAD", "type", "RATE", "value", 150, "totalQuantity", 1,
                "validFrom", "2020-01-01T00:00:00Z", "validUntil", "2030-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOrderReservesStockAndAppliesCoupon() throws Exception {
        long pid = createProduct(1000, 10);
        String rate = createCoupon("RATE", 50, 0, 700L, 5);
        order("u1", UUID.randomUUID().toString(), rate, pid, 3)
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.subtotal").value(3000))
                .andExpect(jsonPath("$.discount").value(700))
                .andExpect(jsonPath("$.totalPrice").value(2300))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1000));
        JsonNode p = product(pid);
        assertThat(p.get("reserved").asInt()).isEqualTo(3);
        assertThat(p.get("available").asInt()).isEqualTo(7);
        mvc.perform(get("/api/coupons/" + rate)).andExpect(jsonPath("$.usedCount").value(1));

        String fixed = createCoupon("FIXED", 5000, 0, null, 5);
        order("u1", UUID.randomUUID().toString(), fixed, pid, 1)
                .andExpect(jsonPath("$.discount").value(1000)).andExpect(jsonPath("$.totalPrice").value(0));
        String min = createCoupon("FIXED", 100, 100000, null, 5);
        order("u1", UUID.randomUUID().toString(), min, pid, 1).andExpect(status().isUnprocessableEntity());
        order("u1", UUID.randomUUID().toString(), "NOPE", pid, 1).andExpect(status().isUnprocessableEntity());
        // failed orders leave no trace
        assertThat(product(pid).get("reserved").asInt()).isEqualTo(4);
    }

    @Test
    void createOrderValidationAndStock() throws Exception {
        long pid = createProduct(100, 2);
        order("u1", "k1", null, pid, 3).andExpect(status().isConflict());
        assertThat(product(pid).get("reserved").asInt()).isZero();
        order("u1", "k2", null, 424242, 1).andExpect(status().isNotFound());
        postJson("/api/orders", Map.of("items", List.of()), "X-User-Id", "u1", "Idempotency-Key", "k3")
                .andExpect(status().isBadRequest());
        postJson("/api/orders", Map.of("items", List.of(Map.of("productId", pid, "quantity", 1))), "X-User-Id", "u1")
                .andExpect(status().isBadRequest());
        postJson("/api/orders", Map.of("items", List.of(Map.of("productId", pid, "quantity", 1))), "Idempotency-Key", "k4")
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOrderIsIdempotent() throws Exception {
        long pid = createProduct(100, 5);
        long first = read(order("u1", "same", null, pid, 2)).get("id").asLong();
        order("u1", "same", null, pid, 2).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(first));
        assertThat(product(pid).get("reserved").asInt()).isEqualTo(2);
        order("u1", "same", null, pid, 3).andExpect(status().isUnprocessableEntity());
        // same key, different user is a different request
        long other = read(order("u2", "same", null, pid, 1)).get("id").asLong();
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void payApprovedCommitsStock() throws Exception {
        long pid = createProduct(500, 5);
        long id = newOrder(pid, 2);
        pay(id, "p1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());
        JsonNode p = product(pid);
        assertThat(p.get("stock").asInt()).isEqualTo(3);
        assertThat(p.get("reserved").asInt()).isZero();
        // replay with same key: no second charge
        pay(id, "p1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(GATEWAY.chargeCalls.get()).isEqualTo(1);
        // different key on a paid order
        pay(id, "p2").andExpect(status().isConflict());
        assertThat(GATEWAY.chargeKeys).containsExactly("p1");
    }

    @Test
    void payDeclinedReleasesStockAndCoupon() throws Exception {
        long pid = createProduct(500, 5);
        String code = createCoupon("FIXED", 100, 0, null, 1);
        long id = read(order("u1", UUID.randomUUID().toString(), code, pid, 2)).get("id").asLong();
        GATEWAY.chargeStatus = "DECLINED";
        pay(id, "p1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAYMENT_FAILED"));
        assertThat(product(pid).get("reserved").asInt()).isZero();
        assertThat(product(pid).get("stock").asInt()).isEqualTo(5);
        mvc.perform(get("/api/coupons/" + code)).andExpect(jsonPath("$.usedCount").value(0));
        pay(id, "p3").andExpect(status().isConflict());
    }

    @Test
    void gatewayFailureKeepsOrderPayable() throws Exception {
        long pid = createProduct(500, 5);
        long id = newOrder(pid, 1);
        GATEWAY.failing = true;
        pay(id, "p1").andExpect(status().isBadGateway());
        mvc.perform(get("/api/orders/" + id)).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        GATEWAY.failing = false;
        pay(id, "p1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void cancelPendingReleasesReservation() throws Exception {
        long pid = createProduct(500, 5);
        long id = newOrder(pid, 2);
        action(id, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(product(pid).get("available").asInt()).isEqualTo(5);
        action(id, "cancel").andExpect(status().isConflict());
        pay(id, "p1").andExpect(status().isConflict());
        assertThat(GATEWAY.chargeCalls.get()).isZero();
    }

    @Test
    void cancelPaidRefundsAndRestocks() throws Exception {
        long pid = createProduct(500, 5);
        long id = newOrder(pid, 2);
        pay(id, "p1");
        action(id, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(GATEWAY.refundCalls.get()).isEqualTo(1);
        assertThat(product(pid).get("stock").asInt()).isEqualTo(5);
        action(id, "cancel").andExpect(status().isConflict());
    }

    @Test
    void refundFailureKeepsOrderPaid() throws Exception {
        long pid = createProduct(500, 5);
        long id = newOrder(pid, 1);
        pay(id, "p1");
        GATEWAY.failing = true;
        action(id, "cancel").andExpect(status().isBadGateway());
        mvc.perform(get("/api/orders/" + id)).andExpect(jsonPath("$.status").value("PAID"));
        GATEWAY.failing = false;
        action(id, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void shippingStateMachine() throws Exception {
        long pid = createProduct(500, 5);
        long id = newOrder(pid, 1);
        action(id, "ship").andExpect(status().isConflict());
        pay(id, "p1");
        action(id, "deliver").andExpect(status().isConflict());
        action(id, "ship").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        action(id, "cancel").andExpect(status().isConflict());
        action(id, "ship").andExpect(status().isConflict());
        action(id, "deliver").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
        action(id, "cancel").andExpect(status().isConflict());
        action(999999, "ship").andExpect(status().isNotFound());
    }

    @Test
    void listWithCursor() throws Exception {
        long pid = createProduct(10, 100);
        String user = "lister-" + UUID.randomUUID();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(read(order(user, UUID.randomUUID().toString(), null, pid, 1)).get("id").asLong());
        }
        pay(ids.get(0), "p");
        JsonNode page1 = read(mvc.perform(get("/api/orders").param("userId", user).param("size", "2")));
        assertThat(page1.get("content")).hasSize(2);
        assertThat(page1.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(4));
        JsonNode page2 = read(mvc.perform(get("/api/orders").param("userId", user).param("size", "2")
                .param("cursor", page1.get("nextCursor").asText())));
        assertThat(page2.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(2));
        JsonNode page3 = read(mvc.perform(get("/api/orders").param("userId", user).param("size", "2")
                .param("cursor", page2.get("nextCursor").asText())));
        assertThat(page3.get("content")).hasSize(1);
        assertThat(page3.get("nextCursor").isNull()).isTrue();
        mvc.perform(get("/api/orders").param("userId", user).param("status", "PAID"))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/orders").param("status", "BOGUS")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("size", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentOrdersNeverOversellStockOrCoupon() throws Exception {
        long pid = createProduct(100, 5);
        String code = createCoupon("FIXED", 10, 0, null, 3);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> order("u", UUID.randomUUID().toString(), null, pid, 1).andReturn().getResponse().getStatus());
        }
        int created = 0;
        for (Future<Integer> f : pool.invokeAll(tasks)) if (f.get() == 201) created++;
        assertThat(created).isEqualTo(5);
        assertThat(product(pid).get("reserved").asInt()).isEqualTo(5);

        long big = createProduct(100, 100);
        tasks.clear();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> order("u", UUID.randomUUID().toString(), code, big, 1).andReturn().getResponse().getStatus());
        }
        int withCoupon = 0;
        for (Future<Integer> f : pool.invokeAll(tasks)) if (f.get() == 201) withCoupon++;
        assertThat(withCoupon).isEqualTo(3);
        assertThat(product(big).get("reserved").asInt()).isEqualTo(3);
        pool.shutdown();
    }

    @Test
    void concurrentSameIdempotencyKeyCreatesOneOrder() throws Exception {
        long pid = createProduct(100, 10);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> read(order("dup", "dup-key", null, pid, 1)).get("id").asLong());
        }
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Future<Long> f : pool.invokeAll(tasks)) ids.add(f.get());
        assertThat(ids).hasSize(1);
        assertThat(product(pid).get("reserved").asInt()).isEqualTo(1);
        pool.shutdown();
    }

    @Test
    void concurrentPaymentsChargeOnlyOnce() throws Exception {
        long pid = createProduct(100, 10);
        long id = newOrder(pid, 1);
        GATEWAY.delayMillis = 300;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String key = "pk-" + i;
            tasks.add(() -> pay(id, key).andReturn().getResponse().getStatus());
        }
        int ok = 0;
        for (Future<Integer> f : pool.invokeAll(tasks)) if (f.get() == 200) ok++;
        assertThat(ok).isEqualTo(1);
        assertThat(GATEWAY.chargeCalls.get()).isEqualTo(1);
        assertThat(product(pid).get("stock").asInt()).isEqualTo(9);
        pool.shutdown();
    }
}
