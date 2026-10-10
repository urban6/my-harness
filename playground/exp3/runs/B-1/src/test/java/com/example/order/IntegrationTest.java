package com.example.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

/** 실제 PostgreSQL(Testcontainers) + 가짜 PG 위에서 API 를 끝까지 태우는 통합 테스트 베이스. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.TestClockConfig.class)
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final FakeGateway GATEWAY = new FakeGateway();

    static {
        POSTGRES.start();
        GATEWAY.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payment.gateway-url", GATEWAY::url);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void resetFixtures() {
        clock.reset();
        GATEWAY.reset();
    }

    // ---------------------------------------------------------------- helpers

    protected long createProduct(String name, long price, int stock) throws Exception {
        return json(mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", name, "price", price, "stock", stock))))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    protected JsonNode product(long id) throws Exception {
        return json(mvc.perform(get("/api/products/" + id)).andExpect(status().isOk()));
    }

    protected String createCoupon(String type, long value, long minOrderAmount, Long maxDiscount, int totalQuantity)
            throws Exception {
        String code = "C-" + UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("type", type);
        body.put("value", value);
        body.put("minOrderAmount", minOrderAmount);
        body.put("maxDiscountAmount", maxDiscount);
        body.put("totalQuantity", totalQuantity);
        body.put("validFrom", clock.instant().minus(Duration.ofDays(1)).toString());
        body.put("validUntil", clock.instant().plus(Duration.ofDays(1)).toString());
        mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        return code;
    }

    protected JsonNode coupon(String code) throws Exception {
        return json(mvc.perform(get("/api/coupons/" + code)).andExpect(status().isOk()));
    }

    /** items: {productId, quantity, ...} 쌍의 나열 */
    protected ResultActions placeOrder(long userId, String key, String couponCode, long... productAndQty)
            throws Exception {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < productAndQty.length; i += 2) {
            items.add(Map.of("productId", productAndQty[i], "quantity", productAndQty[i + 1]));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return mvc.perform(post("/api/orders")
                .header("X-User-Id", userId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    protected JsonNode order(long userId, String couponCode, long... productAndQty) throws Exception {
        return json(placeOrder(userId, UUID.randomUUID().toString(), couponCode, productAndQty)
                .andExpect(status().isCreated()));
    }

    protected ResultActions pay(long orderId, String key, String cardToken) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/pay")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardToken\":\"" + cardToken + "\"}"));
    }

    protected JsonNode paid(long orderId) throws Exception {
        return json(pay(orderId, UUID.randomUUID().toString(), "tok_ok").andExpect(status().isOk()));
    }

    protected ResultActions action(long orderId, String action) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/" + action));
    }

    protected JsonNode getOrder(long orderId) throws Exception {
        return json(mvc.perform(get("/api/orders/" + orderId)).andExpect(status().isOk()));
    }

    protected JsonNode json(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
