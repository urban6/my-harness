package com.example.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final FakeGateway GATEWAY = new FakeGateway();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("app.payment-gateway-url", GATEWAY::url);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void resetGateway() {
        GATEWAY.reset();
    }

    ResultActions postJson(String url, Object body, String... headers) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        for (int i = 0; i < headers.length; i += 2) req.header(headers[i], headers[i + 1]);
        return mvc.perform(req);
    }

    JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    long createProduct(long price, int stock) throws Exception {
        return read(postJson("/api/products", Map.of("name", "item", "price", price, "stock", stock))).get("id").asLong();
    }

    String createCoupon(String type, long value, long min, Long max, int qty) throws Exception {
        String code = "C" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new java.util.HashMap<>(Map.of("code", code, "type", type, "value", value,
                "minOrderAmount", min, "totalQuantity", qty,
                "validFrom", Instant.now().minusSeconds(3600).toString(),
                "validUntil", Instant.now().plusSeconds(3600).toString()));
        if (max != null) body.put("maxDiscountAmount", max);
        postJson("/api/coupons", body).andReturn();
        return code;
    }

    ResultActions order(String user, String key, String coupon, long productId, int qty) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", List.of(Map.of("productId", productId, "quantity", qty)));
        if (coupon != null) body.put("couponCode", coupon);
        return postJson("/api/orders", body, "X-User-Id", user, "Idempotency-Key", key);
    }

    long newOrder(long productId, int qty) throws Exception {
        return read(order("u1", UUID.randomUUID().toString(), null, productId, qty)).get("id").asLong();
    }

    ResultActions pay(long orderId, String key) throws Exception {
        return postJson("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok"), "Idempotency-Key", key);
    }

    ResultActions action(long orderId, String what) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/" + what));
    }

    JsonNode product(long id) throws Exception {
        return read(mvc.perform(get("/api/products/" + id)));
    }
}
