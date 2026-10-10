package com.example.order;

import com.example.order.payment.PaymentGateway.ChargeResult;
import com.example.order.common.error.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderFlowTest extends IntegrationTest {

    @Test
    void createOrder_reservesStockAndReturns201() throws Exception {
        long product = createProduct(1000, 10);

        placeOrder("u1", unique(), items(product, 3), null)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/orders/")))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.subtotal").value(3000))
                .andExpect(jsonPath("$.discount").value(0))
                .andExpect(jsonPath("$.totalPrice").value(3000))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.paidAt").doesNotExist());

        JsonNode p = getProduct(product);
        assertThat(p.get("stock").asInt()).isEqualTo(10);
        assertThat(p.get("reserved").asInt()).isEqualTo(3);
        assertThat(p.get("available").asInt()).isEqualTo(7);
    }

    @Test
    void createOrder_isAtomicAcrossItems_whenOneItemLacksStock() throws Exception {
        long ok = createProduct(1000, 5);
        long scarce = createProduct(1000, 1);

        placeOrder("u1", unique(), "[{\"productId\":%d,\"quantity\":2},{\"productId\":%d,\"quantity\":2}]"
                .formatted(ok, scarce), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(getProduct(ok).get("reserved").asInt()).isZero();
        assertThat(getProduct(scarce).get("reserved").asInt()).isZero();
    }

    @Test
    void createOrder_validatesRequest() throws Exception {
        long product = createProduct(1000, 5);
        placeOrder("u1", unique(), "[]", null).andExpect(status().isBadRequest());
        placeOrder("u1", unique(), items(product, 0), null).andExpect(status().isBadRequest());
        placeOrder("u1", unique(), "[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":1}]"
                .formatted(product, product), null).andExpect(status().isBadRequest());
        placeOrder("u1", unique(), items(999999999L, 1), null).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders").header("X-User-Id", "u1")
                        .contentType("application/json").content("{\"items\":" + items(product, 1) + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("Idempotency-Key", "k")
                        .contentType("application/json").content("{\"items\":" + items(product, 1) + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOrder_isIdempotentPerUserAndKey() throws Exception {
        long product = createProduct(1000, 10);
        String key = unique();

        long first = json(placeOrder("u1", key, items(product, 2), null)).get("id").asLong();
        placeOrder("u1", key, items(product, 2), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(first));
        assertThat(getProduct(product).get("reserved").asInt()).isEqualTo(2);

        placeOrder("u1", key, items(product, 5), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        // 다른 사용자의 같은 키는 별개 주문
        placeOrder("u2", key, items(product, 2), null)
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(first)));
    }

    @Test
    void createOrder_appliesFixedAndRateCoupons() throws Exception {
        long product = createProduct(10000, 100);
        String fixed = createCoupon("FIXED", 3000, null, null, 10);
        String rate = createCoupon("RATE", 20, null, 1500L, 10);
        String huge = createCoupon("FIXED", 999999, null, null, 10);

        placeOrder("u1", unique(), items(product, 1), fixed)
                .andExpect(jsonPath("$.discount").value(3000))
                .andExpect(jsonPath("$.totalPrice").value(7000))
                .andExpect(jsonPath("$.couponCode").value(fixed));
        placeOrder("u1", unique(), items(product, 1), rate)          // 20% = 2000 → 최대 1500
                .andExpect(jsonPath("$.discount").value(1500));
        placeOrder("u1", unique(), items(product, 1), huge)          // 주문 금액 초과 불가
                .andExpect(jsonPath("$.totalPrice").value(0));
        mvc.perform(get("/api/coupons/" + fixed)).andExpect(jsonPath("$.usedCount").value(1));
    }

    @Test
    void createOrder_rejectsInapplicableCoupons_andRollsBackReservation() throws Exception {
        long product = createProduct(1000, 10);
        String minOrder = createCoupon("FIXED", 100, 5000L, null, 10);
        String exhausted = createCoupon("FIXED", 100, null, null, 1);
        placeOrder("u1", unique(), items(product, 1), exhausted).andExpect(status().isCreated());

        placeOrder("u1", unique(), items(product, 1), minOrder).andExpect(status().isUnprocessableEntity());
        placeOrder("u1", unique(), items(product, 1), exhausted)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUPON_EXHAUSTED"));
        placeOrder("u1", unique(), items(product, 1), "NO-SUCH-COUPON").andExpect(status().isNotFound());

        assertThat(getProduct(product).get("reserved").asInt()).isEqualTo(1);   // 성공한 1건만
    }

    @Test
    void pay_approved_marksPaidAndConsumesStock() throws Exception {
        long product = createProduct(1000, 10);
        long order = json(placeOrder("u1", unique(), items(product, 4), null)).get("id").asLong();
        given(gateway.charge(eq(order), eq(4000L), eq("tok_1"), eq("pay-key"))).willReturn(new ChargeResult("pg-1", true));

        pay(order, "pay-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").exists());

        JsonNode p = getProduct(product);
        assertThat(p.get("stock").asInt()).isEqualTo(6);
        assertThat(p.get("reserved").asInt()).isZero();
        assertThat(p.get("available").asInt()).isEqualTo(6);
    }

    @Test
    void pay_sameKeyReplay_doesNotChargeTwice_andOtherKeyConflicts() throws Exception {
        long product = createProduct(1000, 10);
        long order = json(placeOrder("u1", unique(), items(product, 1), null)).get("id").asLong();
        given(gateway.charge(anyLong(), anyLong(), anyString(), anyString())).willReturn(new ChargeResult("pg-1", true));

        pay(order, "k1").andExpect(status().isOk());
        pay(order, "k1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        pay(order, "k2").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));

        verify(gateway, times(1)).charge(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void pay_declined_marksFailedAndReleasesStockAndCoupon() throws Exception {
        long product = createProduct(1000, 10);
        String coupon = createCoupon("FIXED", 100, null, null, 5);
        long order = json(placeOrder("u1", unique(), items(product, 2), coupon)).get("id").asLong();
        given(gateway.charge(anyLong(), anyLong(), anyString(), anyString())).willReturn(new ChargeResult("pg-2", false));

        pay(order, "k1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAYMENT_FAILED"));

        assertThat(getProduct(product).get("reserved").asInt()).isZero();
        assertThat(getProduct(product).get("stock").asInt()).isEqualTo(10);
        mvc.perform(get("/api/coupons/" + coupon)).andExpect(jsonPath("$.usedCount").value(0));
    }

    @Test
    void pay_gatewayFailure_returns502AndKeepsOrderPayable() throws Exception {
        long product = createProduct(1000, 10);
        long order = json(placeOrder("u1", unique(), items(product, 1), null)).get("id").asLong();
        given(gateway.charge(anyLong(), anyLong(), anyString(), anyString()))
                .willThrow(DomainException.upstream("PAYMENT_GATEWAY_ERROR", "down"))
                .willReturn(new ChargeResult("pg-3", true));

        pay(order, "k1").andExpect(status().isBadGateway());
        mvc.perform(get("/api/orders/" + order)).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        pay(order, "k1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void pay_fullyDiscountedOrder_skipsGateway() throws Exception {
        long product = createProduct(1000, 10);
        String coupon = createCoupon("FIXED", 5000, null, null, 5);
        long order = json(placeOrder("u1", unique(), items(product, 1), coupon)).get("id").asLong();

        pay(order, "k1").andExpect(jsonPath("$.status").value("PAID"));
        verify(gateway, never()).charge(anyLong(), anyLong(), anyString(), anyString());
        action(order, "cancel").andExpect(jsonPath("$.status").value("REFUNDED"));
        verify(gateway, never()).refund(any());
    }

    @Test
    void cancel_pending_releasesStockAndCoupon() throws Exception {
        long product = createProduct(1000, 10);
        String coupon = createCoupon("FIXED", 100, null, null, 5);
        long order = json(placeOrder("u1", unique(), items(product, 2), coupon)).get("id").asLong();

        action(order, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(getProduct(product).get("available").asInt()).isEqualTo(10);
        mvc.perform(get("/api/coupons/" + coupon)).andExpect(jsonPath("$.usedCount").value(0));
        action(order, "cancel").andExpect(status().isConflict());
        pay(order, "k1").andExpect(status().isConflict());
    }

    @Test
    void cancel_paid_refundsViaGatewayAndRestocks() throws Exception {
        long product = createProduct(1000, 10);
        long order = json(placeOrder("u1", unique(), items(product, 3), null)).get("id").asLong();
        given(gateway.charge(anyLong(), anyLong(), anyString(), anyString())).willReturn(new ChargeResult("pg-9", true));
        pay(order, "k1").andExpect(status().isOk());

        action(order, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));

        verify(gateway).refund("pg-9");
        JsonNode p = getProduct(product);
        assertThat(p.get("stock").asInt()).isEqualTo(10);
        assertThat(p.get("available").asInt()).isEqualTo(10);
    }

    @Test
    void cancel_paid_whenRefundFails_keepsPaid() throws Exception {
        long product = createProduct(1000, 10);
        long order = json(placeOrder("u1", unique(), items(product, 1), null)).get("id").asLong();
        given(gateway.charge(anyLong(), anyLong(), anyString(), anyString())).willReturn(new ChargeResult("pg-5", true));
        pay(order, "k1").andExpect(status().isOk());
        org.mockito.Mockito.doThrow(DomainException.upstream("PAYMENT_GATEWAY_ERROR", "down")).when(gateway).refund("pg-5");

        action(order, "cancel").andExpect(status().isBadGateway());
        mvc.perform(get("/api/orders/" + order)).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(getProduct(product).get("stock").asInt()).isEqualTo(9);
    }

    @Test
    void shipAndDeliver_followStateMachine() throws Exception {
        long product = createProduct(1000, 10);
        long order = json(placeOrder("u1", unique(), items(product, 1), null)).get("id").asLong();

        action(order, "ship").andExpect(status().isConflict());            // 미결제
        given(gateway.charge(anyLong(), anyLong(), anyString(), anyString())).willReturn(new ChargeResult("pg-7", true));
        pay(order, "k1").andExpect(status().isOk());
        action(order, "deliver").andExpect(status().isConflict());          // 미발송
        action(order, "ship").andExpect(jsonPath("$.status").value("SHIPPED"));
        action(order, "cancel").andExpect(status().isConflict());           // 발송 후 취소 불가
        action(order, "deliver").andExpect(jsonPath("$.status").value("DELIVERED"));
        action(order, "ship").andExpect(status().isConflict());
        action(999999999L, "ship").andExpect(status().isNotFound());
    }

    @Test
    void listOrders_paginatesByCursor_andFilters() throws Exception {
        long product = createProduct(1000, 100);
        String user = "list-" + unique();
        long o1 = json(placeOrder(user, unique(), items(product, 1), null)).get("id").asLong();
        long o2 = json(placeOrder(user, unique(), items(product, 1), null)).get("id").asLong();
        long o3 = json(placeOrder(user, unique(), items(product, 1), null)).get("id").asLong();
        action(o2, "cancel");
        placeOrder("someone-else", unique(), items(product, 1), null);

        JsonNode page1 = json(mvc.perform(get("/api/orders").param("userId", user).param("size", "2")));
        assertThat(page1.get("content")).hasSize(2);
        assertThat(page1.get("content").get(0).get("id").asLong()).isEqualTo(o3);
        assertThat(page1.get("content").get(1).get("id").asLong()).isEqualTo(o2);
        assertThat(page1.get("content").get(0).get("items")).hasSize(1);
        String cursor = page1.get("nextCursor").asText();

        JsonNode page2 = json(mvc.perform(get("/api/orders").param("userId", user).param("size", "2").param("cursor", cursor)));
        assertThat(page2.get("content")).hasSize(1);
        assertThat(page2.get("content").get(0).get("id").asLong()).isEqualTo(o1);
        assertThat(page2.get("nextCursor").isNull()).isTrue();

        JsonNode cancelled = json(mvc.perform(get("/api/orders").param("userId", user).param("status", "CANCELLED")));
        assertThat(cancelled.get("content")).hasSize(1);

        mvc.perform(get("/api/orders").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("cursor", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("status", "WHATEVER")).andExpect(status().isBadRequest());
    }
}
