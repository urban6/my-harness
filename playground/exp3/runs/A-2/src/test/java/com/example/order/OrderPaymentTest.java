package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OrderPaymentTest extends ApiTestSupport {

    @Test
    void approvedPaymentMarksPaidAndCommitsStock() throws Exception {
        long p = createProduct(10000, 5);
        JsonNode order = newOrder("u-" + uid(), null, p, 2);
        long id = order.get("id").asLong();
        String key = "pay-" + uid();

        pay(id, key, "tok_ok")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").exists());

        JsonNode product = product(p);
        assertThat(product.get("stock").asInt()).isEqualTo(3);
        assertThat(product.get("reserved").asInt()).isZero();
        assertThat(product.get("available").asInt()).isEqualTo(3);

        var charges = PG.charges(id);
        assertThat(charges).hasSize(1);
        assertThat(charges.get(0).idempotencyKey()).isEqualTo(key);
        assertThat(charges.get(0).body().get("amount").asLong()).isEqualTo(20000);
        assertThat(charges.get(0).body().get("cardToken").asText()).isEqualTo("tok_ok");
        assertThat(order(id).get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void paymentUsesDiscountedTotal() throws Exception {
        long p = createProduct(10000, 5);
        String code = createCoupon("FIXED", 2500, 0, null, 3);
        long id = newOrder("u-" + uid(), code, p, 1).get("id").asLong();
        pay(id, "pay-" + uid(), "tok_ok").andExpect(status().isOk());
        assertThat(PG.charges(id).get(0).body().get("amount").asLong()).isEqualTo(7500);
    }

    @Test
    void declinedPaymentFailsOrderAndReleasesStockAndCoupon() throws Exception {
        long p = createProduct(10000, 5);
        String code = createCoupon("FIXED", 1000, 0, null, 3);
        long id = newOrder("u-" + uid(), code, p, 2).get("id").asLong();
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);

        pay(id, "pay-" + uid(), FakePaymentGateway.DECLINE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.paidAt").doesNotExist());

        JsonNode product = product(p);
        assertThat(product.get("stock").asInt()).isEqualTo(5);
        assertThat(product.get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();

        // 실패한 주문은 다시 결제할 수 없다
        pay(id, "pay-" + uid(), "tok_ok").andExpect(status().isConflict());
    }

    @Test
    void sameIdempotencyKeyDoesNotChargeTwice() throws Exception {
        long p = createProduct(1000, 5);
        long id = newOrder("u-" + uid(), null, p, 1).get("id").asLong();
        String key = "pay-" + uid();

        pay(id, key, "tok_ok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        pay(id, key, "tok_ok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

        assertThat(PG.charges(id)).hasSize(1);
        assertThat(product(p).get("stock").asInt()).isEqualTo(4);

        pay(id, key, "tok_other")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        // 다른 키로 이미 결제된 주문을 다시 결제하면 상태 충돌
        pay(id, "pay-" + uid(), "tok_ok").andExpect(status().isConflict());
        assertThat(PG.charges(id)).hasSize(1);
    }

    @Test
    void concurrentPaymentsReachTheGatewayOnce() throws Exception {
        long p = createProduct(1000, 5);
        long id = newOrder("u-" + uid(), null, p, 1).get("id").asLong();

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String key = "pay-" + uid();
            tasks.add(() -> pay(id, key, FakePaymentGateway.SLOW).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = OrderCreationTest.runConcurrently(tasks);

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(5);
        assertThat(PG.charges(id)).hasSize(1);
        assertThat(product(p).get("stock").asInt()).isEqualTo(4);
    }

    @Test
    void concurrentPaymentsWithSameKeyAreAllServed() throws Exception {
        long p = createProduct(1000, 5);
        long id = newOrder("u-" + uid(), null, p, 1).get("id").asLong();
        String key = "pay-" + uid();

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> pay(id, key, FakePaymentGateway.SLOW).andReturn().getResponse().getStatus());
        }
        assertThat(OrderCreationTest.runConcurrently(tasks)).allMatch(s -> s == 200);
        assertThat(PG.charges(id)).hasSize(1);
    }

    @Test
    void gatewayFailureKeepsOrderPayableAndRetryWithSameKeyWorks() throws Exception {
        long p = createProduct(1000, 5);
        long id = newOrder("u-" + uid(), null, p, 1).get("id").asLong();
        String key = "pay-" + uid();

        pay(id, key, FakePaymentGateway.ERROR)
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(order(id).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);

        pay(id, key, "tok_ok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void payValidatesInput() throws Exception {
        long p = createProduct(1000, 5);
        long id = newOrder("u-" + uid(), null, p, 1).get("id").asLong();
        postJson("/api/orders/" + id + "/pay", java.util.Map.of("cardToken", ""), "Idempotency-Key", "k-" + uid())
                .andExpect(status().isBadRequest());
        postJson("/api/orders/" + id + "/pay", java.util.Map.of("cardToken", "tok_ok"))
                .andExpect(status().isBadRequest());
        pay(987654321L, "k-" + uid(), "tok_ok").andExpect(status().isNotFound());
    }
}
