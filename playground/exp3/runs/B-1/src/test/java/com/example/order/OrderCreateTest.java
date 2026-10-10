package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OrderCreateTest extends IntegrationTest {

    @Test
    void create_reservesStock_andReturnsPendingOrder() throws Exception {
        long p1 = createProduct("A", 1000, 10);
        long p2 = createProduct("B", 2500, 10);

        placeOrder(7, "k-1", null, p1, 2, p2, 1)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(".*/api/orders/\\d+")))
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.subtotal").value(4500))
                .andExpect(jsonPath("$.discount").value(0))
                .andExpect(jsonPath("$.totalPrice").value(4500))
                .andExpect(jsonPath("$.couponCode").doesNotExist())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.paidAt").doesNotExist());

        assertThat(product(p1).get("reserved").asInt()).isEqualTo(2);
        assertThat(product(p1).get("available").asInt()).isEqualTo(8);
        assertThat(product(p2).get("available").asInt()).isEqualTo(9);
    }

    @Test
    void create_expiresAt_isCreatedAtPlusTtl() throws Exception {
        long p = createProduct("A", 1000, 10);

        JsonNode o = order(1, null, p, 1);

        Duration ttl = Duration.between(java.time.Instant.parse(o.get("createdAt").asText()),
                java.time.Instant.parse(o.get("expiresAt").asText()));
        assertThat(ttl).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void create_mergesDuplicateProductLines() throws Exception {
        long p = createProduct("A", 1000, 10);

        JsonNode o = order(1, null, p, 2, p, 3);

        assertThat(o.get("items")).hasSize(1);
        assertThat(o.get("items").get(0).get("quantity").asInt()).isEqualTo(5);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(5);
    }

    @Test
    void create_returns409_andLeavesNoReservation_whenOutOfStock() throws Exception {
        long plenty = createProduct("A", 1000, 10);
        long scarce = createProduct("B", 1000, 1);
        String code = createCoupon("FIXED", 500, 0, null, 10);

        placeOrder(1, "k-oos", code, plenty, 2, scarce, 2)
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/problem+json")));

        assertThat(product(plenty).get("reserved").asInt()).isZero();
        assertThat(product(scarce).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
    }

    @Test
    void create_returns404_whenProductMissing() throws Exception {
        placeOrder(1, "k-404", null, 999999, 1).andExpect(status().isNotFound());
    }

    @Test
    void create_withFixedCoupon_appliesDiscount_andCountsUsage() throws Exception {
        long p = createProduct("A", 10000, 10);
        String code = createCoupon("FIXED", 3000, 5000, null, 10);

        JsonNode o = order(1, code, p, 1);

        assertThat(o.get("couponCode").asText()).isEqualTo(code);
        assertThat(o.get("subtotal").asLong()).isEqualTo(10000);
        assertThat(o.get("discount").asLong()).isEqualTo(3000);
        assertThat(o.get("totalPrice").asLong()).isEqualTo(7000);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    void create_withRateCoupon_capsAtMaxDiscount() throws Exception {
        long p = createProduct("A", 100000, 10);
        String code = createCoupon("RATE", 20, 0, 5000L, 10);

        JsonNode o = order(1, code, p, 1);

        assertThat(o.get("discount").asLong()).isEqualTo(5000);
        assertThat(o.get("totalPrice").asLong()).isEqualTo(95000);
    }

    @Test
    void create_withRateCoupon_floorsFraction() throws Exception {
        long p = createProduct("A", 1005, 10);
        String code = createCoupon("RATE", 10, 0, null, 10);

        JsonNode o = order(1, code, p, 1);

        assertThat(o.get("discount").asLong()).isEqualTo(100);
    }

    @Test
    void create_fixedCoupon_neverExceedsSubtotal() throws Exception {
        long p = createProduct("A", 1000, 10);
        String code = createCoupon("FIXED", 5000, 0, null, 10);

        JsonNode o = order(1, code, p, 1);

        assertThat(o.get("discount").asLong()).isEqualTo(1000);
        assertThat(o.get("totalPrice").asLong()).isZero();
    }

    @Test
    void create_returns422_whenMinOrderAmountNotMet_andNoSideEffects() throws Exception {
        long p = createProduct("A", 1000, 10);
        String code = createCoupon("FIXED", 500, 5000, null, 10);

        placeOrder(1, "k-min", code, p, 1).andExpect(status().isUnprocessableEntity());

        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
    }

    @Test
    void create_returns422_whenCouponOutsidePeriod() throws Exception {
        long p = createProduct("A", 1000, 10);
        String code = createCoupon("FIXED", 500, 0, null, 10);
        clock.advance(Duration.ofDays(2));

        placeOrder(1, "k-period", code, p, 1).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void create_returns422_whenCouponExhausted() throws Exception {
        long p = createProduct("A", 1000, 10);
        String code = createCoupon("FIXED", 500, 0, null, 1);
        order(1, code, p, 1);

        placeOrder(2, "k-exhausted", code, p, 1).andExpect(status().isUnprocessableEntity());
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    void create_returns404_whenCouponMissing() throws Exception {
        long p = createProduct("A", 1000, 10);
        placeOrder(1, "k-nocoupon", "NOPE", p, 1).andExpect(status().isNotFound());
    }

    @Test
    void create_sameIdempotencyKey_returnsSameOrder_withoutReservingTwice() throws Exception {
        long p = createProduct("A", 1000, 10);

        JsonNode first = json(placeOrder(1, "k-same", null, p, 2).andExpect(status().isCreated()));
        JsonNode second = json(placeOrder(1, "k-same", null, p, 2).andExpect(status().isCreated()));

        assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    void create_sameKeyDifferentPayload_returns409() throws Exception {
        long p = createProduct("A", 1000, 10);
        placeOrder(1, "k-diff", null, p, 2).andExpect(status().isCreated());

        placeOrder(1, "k-diff", null, p, 3).andExpect(status().isConflict());
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    void create_sameKeyDifferentUser_createsSeparateOrders() throws Exception {
        long p = createProduct("A", 1000, 10);

        JsonNode a = json(placeOrder(1, "k-shared", null, p, 1).andExpect(status().isCreated()));
        JsonNode b = json(placeOrder(2, "k-shared", null, p, 1).andExpect(status().isCreated()));

        assertThat(a.get("id").asLong()).isNotEqualTo(b.get("id").asLong());
    }

    @Test
    void create_returns400_whenHeadersMissingOrBodyInvalid() throws Exception {
        long p = createProduct("A", 1000, 10);
        String body = "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}]}";

        mvc.perform(post("/api/orders").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("X-User-Id", 1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("X-User-Id", "abc").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("X-User-Id", 1).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("X-User-Id", 1).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":" + p + ",\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_returnsOrder_or404() throws Exception {
        long p = createProduct("A", 1000, 10);
        JsonNode o = order(1, null, p, 1);

        assertThat(getOrder(o.get("id").asLong()).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        mvc.perform(get("/api/orders/999999")).andExpect(status().isNotFound());
    }

    @Test
    void list_paginatesByCursor_newestFirst_andFiltersByUserAndStatus() throws Exception {
        long p = createProduct("A", 100, 1000);
        long user = 9_000_000L + (System.nanoTime() % 1_000_000L);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(order(user, null, p, 1).get("id").asLong());
        }
        order(user + 1, null, p, 1); // 다른 사용자
        action(ids.get(0), "cancel").andExpect(status().isOk());

        JsonNode page1 = json(mvc.perform(get("/api/orders").param("userId", String.valueOf(user)).param("size", "2"))
                .andExpect(status().isOk()));
        assertThat(page1.get("content")).hasSize(2);
        assertThat(page1.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(4));
        assertThat(page1.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(3));
        String cursor = page1.get("nextCursor").asText();

        JsonNode page2 = json(mvc.perform(get("/api/orders").param("userId", String.valueOf(user))
                .param("size", "2").param("cursor", cursor)).andExpect(status().isOk()));
        assertThat(page2.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(2));
        assertThat(page2.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(1));

        JsonNode page3 = json(mvc.perform(get("/api/orders").param("userId", String.valueOf(user))
                .param("size", "2").param("cursor", page2.get("nextCursor").asText())).andExpect(status().isOk()));
        assertThat(page3.get("content")).hasSize(1);
        assertThat(page3.get("nextCursor").isNull()).isTrue();

        JsonNode cancelled = json(mvc.perform(get("/api/orders").param("userId", String.valueOf(user))
                .param("status", "CANCELLED")).andExpect(status().isOk()));
        assertThat(cancelled.get("content")).hasSize(1);
        assertThat(cancelled.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(0));
    }

    @Test
    void list_returns400_onBadSizeCursorOrStatus() throws Exception {
        mvc.perform(get("/api/orders").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("cursor", "!!!")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("status", "NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void create_idempotencyKeyOnlyBlank_returns400() throws Exception {
        long p = createProduct("A", 1000, 10);
        placeOrder(1, " ", null, p, 1).andExpect(status().isBadRequest());
        placeOrder(1, UUID.randomUUID().toString(), null, p, 1).andExpect(status().isCreated());
    }
}
