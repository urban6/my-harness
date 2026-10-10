package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class OrderLifecycleTest extends IntegrationTestBase {

    // ---------------------------------------------------------------- 결제

    @Test
    void approvedPaymentMarksPaidAndCommitsStock() throws Exception {
        long productId = createProduct(1000, 10);
        long id = order("u1", productId, 3);

        pay(id, "pay-key-1", "tok_ok")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").exists());

        JsonNode p = product(productId);
        assertThat(p.get("stock").asLong()).isEqualTo(7);
        assertThat(p.get("reserved").asLong()).isZero();
        assertThat(p.get("available").asLong()).isEqualTo(7);

        assertThat(PG.chargeKeys).containsExactly("pay-key-1");
        JsonNode sent = PG.chargeBodies.get(0);
        assertThat(sent.get("orderId").asLong()).isEqualTo(id);
        assertThat(sent.get("amount").asLong()).isEqualTo(3000);
        assertThat(sent.get("cardToken").asText()).isEqualTo("tok_ok");
    }

    @Test
    void chargesTheDiscountedTotal() throws Exception {
        long productId = createProduct(1000, 10);
        String code = createCoupon("FIXED", 400, 0, null, 5);
        long id = read(placeOrder("u1", null, orderBody(code, productId, 2))).get("id").asLong();

        pay(id, "k", "tok_ok").andExpect(status().isOk());

        assertThat(PG.chargeBodies.get(0).get("amount").asLong()).isEqualTo(1600);
    }

    @Test
    void retryWithTheSamePaymentKeyIsIdempotentButAnotherKeyConflicts() throws Exception {
        long productId = createProduct(1000, 10);
        long id = order("u1", productId, 1);

        pay(id, "same-key", "tok_ok").andExpect(status().isOk());
        pay(id, "same-key", "tok_ok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(PG.chargeKeys).hasSize(1);
        assertThat(product(productId).get("stock").asLong()).isEqualTo(9);

        pay(id, "other-key", "tok_ok").andExpect(status().isConflict());
        assertThat(PG.chargeKeys).hasSize(1);
    }

    @Test
    void declinedPaymentFailsOrderAndReleasesReservationAndCoupon() throws Exception {
        long productId = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 0, null, 5);
        long id = read(placeOrder("u1", null, orderBody(code, productId, 2))).get("id").asLong();
        assertThat(product(productId).get("reserved").asLong()).isEqualTo(2);

        pay(id, "k", "tok_decline")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.paidAt").doesNotExist());

        JsonNode p = product(productId);
        assertThat(p.get("stock").asLong()).isEqualTo(10);
        assertThat(p.get("reserved").asLong()).isZero();
        assertThat(coupon(code).get("usedCount").asLong()).isZero();
        pay(id, "k2", "tok_ok").andExpect(status().isConflict());
    }

    @Test
    void gatewayFailureKeepsOrderPayableAndRetryWithSameKeyWorks() throws Exception {
        long productId = createProduct(1000, 10);
        long id = order("u1", productId, 1);

        pay(id, "retry-key", "tok_error").andExpect(status().isBadGateway());
        assertThat(getOrder(id).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(product(productId).get("reserved").asLong()).isEqualTo(1);

        pay(id, "retry-key", "tok_ok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(PG.chargeKeys).containsExactly("retry-key", "retry-key");
    }

    @Test
    void paymentWithoutKeyUsesAStableKeyPerOrder() throws Exception {
        long id = order("u1", createProduct(1000, 10), 1);
        pay(id, null, "tok_ok").andExpect(status().isOk());
        pay(id, null, "tok_ok").andExpect(status().isOk());
        assertThat(PG.chargeKeys).hasSize(1).first().isEqualTo("pay-" + id);
    }

    @Test
    void payValidatesBodyAndOrderExistence() throws Exception {
        long id = order("u1", createProduct(1000, 10), 1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders/" + id + "/pay")
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        pay(999999999L, "k", "tok_ok").andExpect(status().isNotFound());
    }

    @Test
    void concurrentPaymentsOnOneOrderChargeOnce() throws Exception {
        long productId = createProduct(1000, 10);
        long id = order("u1", productId, 1);

        List<Integer> statuses = runConcurrently(6, i -> pay(id, "key-" + i, "tok_ok").andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(5);
        assertThat(PG.chargeKeys).hasSize(1);
        assertThat(product(productId).get("stock").asLong()).isEqualTo(9);
    }

    // ---------------------------------------------------------------- 취소 / 환불

    @Test
    void cancellingPendingOrderReleasesReservationAndCoupon() throws Exception {
        long productId = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 0, null, 5);
        long id = read(placeOrder("u1", null, orderBody(code, productId, 4))).get("id").asLong();

        action(id, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(product(productId).get("reserved").asLong()).isZero();
        assertThat(product(productId).get("stock").asLong()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asLong()).isZero();
        assertThat(PG.refundedPaymentIds).isEmpty();

        action(id, "cancel").andExpect(status().isConflict());
        assertThat(product(productId).get("reserved").asLong()).isZero(); // 이중 해제 없음
        pay(id, "k", "tok_ok").andExpect(status().isConflict());
    }

    @Test
    void cancellingPaidOrderRefundsAndRestocks() throws Exception {
        long productId = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 0, null, 5);
        long id = read(placeOrder("u1", null, orderBody(code, productId, 4))).get("id").asLong();
        pay(id, "k", "tok_ok").andExpect(status().isOk());
        assertThat(product(productId).get("stock").asLong()).isEqualTo(6);

        action(id, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));

        assertThat(PG.refundedPaymentIds).hasSize(1);
        assertThat(product(productId).get("stock").asLong()).isEqualTo(10);
        assertThat(product(productId).get("available").asLong()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asLong()).isZero();
        action(id, "cancel").andExpect(status().isConflict());
    }

    @Test
    void refundFailureLeavesOrderPaid() throws Exception {
        long productId = createProduct(1000, 10);
        long id = order("u1", productId, 2);
        pay(id, "k", "tok_ok").andExpect(status().isOk());
        PG.failRefunds = true;

        action(id, "cancel").andExpect(status().isBadGateway());

        assertThat(getOrder(id).get("status").asText()).isEqualTo("PAID");
        assertThat(product(productId).get("stock").asLong()).isEqualTo(8);

        PG.failRefunds = false;
        action(id, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    // ---------------------------------------------------------------- 배송

    @Test
    void shipAndDeliverFollowTheStateMachine() throws Exception {
        long productId = createProduct(1000, 10);
        long id = order("u1", productId, 1);

        action(id, "ship").andExpect(status().isConflict()); // 미결제
        pay(id, "k", "tok_ok").andExpect(status().isOk());
        action(id, "deliver").andExpect(status().isConflict()); // 미배송
        action(id, "ship").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        action(id, "ship").andExpect(status().isConflict());
        action(id, "cancel").andExpect(status().isConflict()); // 배송 후 취소 불가
        action(id, "deliver").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
        action(id, "deliver").andExpect(status().isConflict());
        action(id, "cancel").andExpect(status().isConflict());
        action(999999999L, "ship").andExpect(status().isNotFound());
        assertThat(PG.refundedPaymentIds).isEmpty();
    }

    // ---------------------------------------------------------------- 목록

    @Test
    void listsOrdersWithCursorPaginationAndFilters() throws Exception {
        String user = "lister-" + UUID.randomUUID();
        long productId = createProduct(100, 100);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(order(user, productId, 1));
        }
        order("someone-else", productId, 1);
        pay(ids.get(1), "k1", "tok_ok").andExpect(status().isOk());
        action(ids.get(3), "cancel").andExpect(status().isOk());

        // 최신순, 2개씩 3페이지
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = "/api/orders?userId=" + user + "&size=2" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = read(mvc.perform(get(url)).andExpect(status().isOk()));
            page.get("content").forEach(o -> seen.add(o.get("id").asLong()));
            assertThat(page.get("content").size()).isLessThanOrEqualTo(2);
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        List<Long> expected = new ArrayList<>(ids);
        java.util.Collections.reverse(expected);
        assertThat(seen).isEqualTo(expected);

        JsonNode paid = read(mvc.perform(get("/api/orders?userId=" + user + "&status=PAID")));
        assertThat(paid.get("content")).hasSize(1);
        assertThat(paid.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(1));
        assertThat(paid.get("nextCursor").isNull()).isTrue();

        JsonNode cancelled = read(mvc.perform(get("/api/orders?userId=" + user + "&status=CANCELLED")));
        assertThat(cancelled.get("content")).hasSize(1);
    }

    @Test
    void listRejectsBadParameters() throws Exception {
        mvc.perform(get("/api/orders?size=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders?cursor=abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders?status=NOPE")).andExpect(status().isBadRequest());
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
