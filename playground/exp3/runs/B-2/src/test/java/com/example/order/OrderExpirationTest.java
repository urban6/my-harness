package com.example.order;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 결제 TTL을 0으로 두어 주문이 생성 즉시 만료 대상이 되게 한다. */
@TestPropertySource(properties = {"order.payment-ttl=PT0S", "order.expiry-sweep-interval=PT1H"})
class OrderExpirationTest extends IntegrationTest {

    @Test
    void getOrder_expiresLazily_andReleasesStockAndCoupon() throws Exception {
        long product = createProduct(1000, 10);
        String coupon = createCoupon("FIXED", 100, null, null, 5);
        long order = json(placeOrder("u1", unique(), items(product, 2), coupon)).get("id").asLong();

        mvc.perform(get("/api/orders/" + order)).andExpect(jsonPath("$.status").value("EXPIRED"));

        assertThat(getProduct(product).get("reserved").asInt()).isZero();
        mvc.perform(get("/api/coupons/" + coupon)).andExpect(jsonPath("$.usedCount").value(0));
    }

    @Test
    void pay_onExpiredOrder_returns409_andDoesNotCallGateway() throws Exception {
        long product = createProduct(1000, 10);
        long order = json(placeOrder("u1", unique(), items(product, 1), null)).get("id").asLong();

        pay(order, "k1").andExpect(status().isConflict());

        verify(gateway, never()).charge(anyLong(), anyLong(), anyString(), anyString());
        assertThat(getProduct(product).get("reserved").asInt()).isZero();   // 만료 처리가 커밋됨
        mvc.perform(get("/api/orders/" + order)).andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void listOrders_sweepsExpiredOrders() throws Exception {
        long product = createProduct(1000, 10);
        String user = "exp-" + unique();
        placeOrder(user, unique(), items(product, 1), null);
        placeOrder(user, unique(), items(product, 1), null);

        JsonNode expired = json(mvc.perform(get("/api/orders").param("userId", user).param("status", "EXPIRED")));

        assertThat(expired.get("content")).hasSize(2);
        assertThat(getProduct(product).get("available").asInt()).isEqualTo(10);
    }
}
