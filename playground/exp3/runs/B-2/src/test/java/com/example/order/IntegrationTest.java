package com.example.order;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

import com.example.order.payment.PaymentGateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();   // 테스트 클래스 간 컨테이너 1개 공유(JVM 종료 시 정리)
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper objectMapper;
    @MockitoBean protected PaymentGateway gateway;

    protected JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected long createProduct(long price, int stock) throws Exception {
        return json(postJson("/api/products", """
                {"name":"상품","price":%d,"stock":%d}""".formatted(price, stock))).get("id").asLong();
    }

    protected JsonNode getProduct(long id) throws Exception {
        return json(mvc.perform(get("/api/products/" + id)));
    }

    protected String createCoupon(String type, long value, Long min, Long max, int total) throws Exception {
        String code = "C-" + UUID.randomUUID();
        postJson("/api/coupons", """
                {"code":"%s","type":"%s","value":%d,"minOrderAmount":%s,"maxDiscountAmount":%s,"totalQuantity":%d,
                 "validFrom":"2020-01-01T00:00:00Z","validUntil":"2099-01-01T00:00:00Z"}"""
                .formatted(code, type, value, min, max, total)).andReturn();
        return code;
    }

    protected ResultActions placeOrder(String userId, String key, String itemsJson, String couponCode) throws Exception {
        String coupon = couponCode == null ? "" : ",\"couponCode\":\"" + couponCode + "\"";
        return mvc.perform(post("/api/orders")
                .header("X-User-Id", userId).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":" + itemsJson + coupon + "}"));
    }

    protected String items(long productId, int quantity) {
        return "[{\"productId\":%d,\"quantity\":%d}]".formatted(productId, quantity);
    }

    protected ResultActions pay(long orderId, String key) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/pay").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"cardToken\":\"tok_1\"}"));
    }

    protected ResultActions action(long orderId, String action) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/" + action));
    }

    protected long newId() {
        return System.nanoTime();
    }

    protected String unique() {
        return UUID.randomUUID().toString();
    }
}
