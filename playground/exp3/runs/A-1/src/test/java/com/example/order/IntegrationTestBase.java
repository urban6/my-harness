package com.example.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final StubPaymentGateway PG = new StubPaymentGateway();

    static {
        POSTGRES.start();
        PG.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payment.gateway.url", PG::url);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;

    @BeforeEach
    void resetGateway() {
        PG.reset();
    }

    // ------------------------------------------------------------ 요청 헬퍼

    protected ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode read(ResultActions actions) throws Exception {
        return read(actions.andReturn());
    }

    protected long createProduct(long price, long stock) throws Exception {
        return read(postJson("/api/products", Map.of("name", "item-" + UUID.randomUUID(), "price", price, "stock", stock)))
                .get("id").asLong();
    }

    protected JsonNode product(long id) throws Exception {
        return read(mvc.perform(get("/api/products/" + id)));
    }

    protected String createCoupon(String type, long value, long minOrderAmount, Long maxDiscount, long quantity)
            throws Exception {
        String code = "C-" + UUID.randomUUID();
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "code", code, "type", type, "value", value, "minOrderAmount", minOrderAmount,
                "totalQuantity", quantity, "validFrom", "2020-01-01T00:00:00Z", "validUntil", "2999-01-01T00:00:00Z"));
        if (maxDiscount != null) {
            body.put("maxDiscountAmount", maxDiscount);
        }
        postJson("/api/coupons", body);
        return code;
    }

    protected JsonNode coupon(String code) throws Exception {
        return read(mvc.perform(get("/api/coupons/" + code)));
    }

    protected Map<String, Object> orderBody(String couponCode, long... productIdAndQty) {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < productIdAndQty.length; i += 2) {
            items.add(Map.of("productId", productIdAndQty[i], "quantity", productIdAndQty[i + 1]));
        }
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return body;
    }

    protected ResultActions placeOrder(String userId, String idempotencyKey, Map<String, Object> body)
            throws Exception {
        var req = post("/api/orders").contentType(MediaType.APPLICATION_JSON).header("X-User-Id", userId)
                .content(json.writeValueAsString(body));
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(req);
    }

    /** 주문을 만들고 id를 돌려준다(201 가정). */
    protected long order(String userId, long productId, long qty) throws Exception {
        return read(placeOrder(userId, UUID.randomUUID().toString(), orderBody(null, productId, qty))).get("id")
                .asLong();
    }

    protected ResultActions pay(long orderId, String idempotencyKey, String cardToken) throws Exception {
        var req = post("/api/orders/" + orderId + "/pay").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardToken\":\"" + cardToken + "\"}");
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(req);
    }

    protected JsonNode getOrder(long id) throws Exception {
        return read(mvc.perform(get("/api/orders/" + id)));
    }

    protected ResultActions action(long orderId, String verb) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/" + verb));
    }
}
