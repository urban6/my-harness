package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderLifecycleTest extends ApiTestSupport {

    private long paidOrder(String user, long productId, int qty, String couponCode, String token) throws Exception {
        long id = newOrder(user, couponCode, productId, qty).get("id").asLong();
        pay(id, "pay-" + uid(), token).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        return id;
    }

    @Test
    void cancelPendingOrderReleasesStockAndCoupon() throws Exception {
        long p = createProduct(1000, 5);
        String code = createCoupon("FIXED", 100, 0, null, 1);
        long id = newOrder("u-" + uid(), code, p, 2).get("id").asLong();

        action(id, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(product(p).get("stock").asInt()).isEqualTo(5);
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        // 쿠폰을 다시 쓸 수 있다
        newOrder("u-" + uid(), code, p, 1);

        action(id, "cancel").andExpect(status().isConflict());
        pay(id, "pay-" + uid(), "tok_ok").andExpect(status().isConflict());
    }

    @Test
    void cancelPaidOrderRefundsAndRestocks() throws Exception {
        long p = createProduct(1000, 5);
        String code = createCoupon("FIXED", 100, 0, null, 1);
        long id = paidOrder("u-" + uid(), p, 2, code, "tok_ok");
        assertThat(product(p).get("stock").asInt()).isEqualTo(3);
        String paymentId = PG.paymentIdFor(id);
        assertThat(PG.refundCount(paymentId)).isZero();

        action(id, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));

        assertThat(product(p).get("stock").asInt()).isEqualTo(5);
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        assertThat(PG.refundCount(paymentId)).isEqualTo(1);
        action(id, "cancel").andExpect(status().isConflict());
        action(id, "ship").andExpect(status().isConflict());
    }

    @Test
    void refundFailureKeepsOrderPaid() throws Exception {
        long p = createProduct(1000, 5);
        long id = paidOrder("u-" + uid(), p, 1, null, FakePaymentGateway.NO_REFUND);

        action(id, "cancel").andExpect(status().isBadGateway());

        assertThat(order(id).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asInt()).isEqualTo(4);
    }

    @Test
    void shipAndDeliverFollowTheStateMachine() throws Exception {
        long p = createProduct(1000, 5);
        long pending = newOrder("u-" + uid(), null, p, 1).get("id").asLong();
        action(pending, "ship").andExpect(status().isConflict());
        action(pending, "deliver").andExpect(status().isConflict());

        long id = paidOrder("u-" + uid(), p, 1, null, "tok_ok");
        action(id, "deliver").andExpect(status().isConflict());
        action(id, "ship").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        action(id, "ship").andExpect(status().isConflict());
        action(id, "cancel").andExpect(status().isConflict());
        action(id, "deliver").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
        action(id, "deliver").andExpect(status().isConflict());
        action(id, "cancel").andExpect(status().isConflict());

        action(987654321L, "ship").andExpect(status().isNotFound());
    }

    @Test
    void listsOrdersWithCursorPaginationAndFilters() throws Exception {
        long p = createProduct(1000, 100);
        String user = "u-" + uid();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(newOrder(user, null, p, 1).get("id").asLong());
        }
        newOrder("u-" + uid(), null, p, 1);                       // 다른 사용자
        long paid = paidOrder(user, p, 1, null, "tok_ok");
        ids.add(paid);

        // 최신순, 페이지 크기 4 → 4 + 2
        JsonNode page1 = body(mvc.perform(get("/api/orders").param("userId", user).param("size", "4"))
                .andExpect(status().isOk()));
        assertThat(page1.get("content")).hasSize(4);
        assertThat(page1.get("nextCursor").isNull()).isFalse();
        assertThat(page1.get("content").get(0).get("id").asLong()).isEqualTo(paid);

        JsonNode page2 = body(mvc.perform(get("/api/orders").param("userId", user).param("size", "4")
                .param("cursor", page1.get("nextCursor").asText())).andExpect(status().isOk()));
        assertThat(page2.get("content")).hasSize(2);
        assertThat(page2.get("nextCursor").isNull()).isTrue();

        List<Long> seen = new ArrayList<>();
        page1.get("content").forEach(n -> seen.add(n.get("id").asLong()));
        page2.get("content").forEach(n -> seen.add(n.get("id").asLong()));
        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);

        JsonNode onlyPaid = body(mvc.perform(get("/api/orders").param("userId", user).param("status", "PAID")));
        assertThat(onlyPaid.get("content")).hasSize(1);
        assertThat(onlyPaid.get("content").get(0).get("id").asLong()).isEqualTo(paid);
        assertThat(onlyPaid.get("nextCursor").isNull()).isTrue();

        mvc.perform(get("/api/orders").param("status", "BOGUS")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("cursor", "abc")).andExpect(status().isBadRequest());
    }
}
