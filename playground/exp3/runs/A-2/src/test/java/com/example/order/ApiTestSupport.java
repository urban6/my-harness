package com.example.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest
@AutoConfigureMockMvc
abstract class ApiTestSupport {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final FakePaymentGateway PG = new FakePaymentGateway();

    static {
        POSTGRES.start();
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

    // ------------------------------------------------------------ helpers

    protected static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    protected ResultActions postJson(String url, Object payload, String... headers) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        for (int i = 0; i < headers.length; i += 2) {
            req.header(headers[i], headers[i + 1]);
        }
        return mvc.perform(req);
    }

    protected long createProduct(long price, int stock) throws Exception {
        ResultActions r = postJson("/api/products", Map.of("name", "item-" + uid(), "price", price, "stock", stock));
        r.andReturn().getResponse();
        return body(r).get("id").asLong();
    }

    protected JsonNode product(long id) throws Exception {
        return body(mvc.perform(get("/api/products/" + id)));
    }

    protected String createCoupon(String type, long value, long minOrder, Long maxDiscount, int quantity)
            throws Exception {
        return createCoupon(type, value, minOrder, maxDiscount, quantity,
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS));
    }

    protected String createCoupon(String type, long value, long minOrder, Long maxDiscount, int quantity,
            Instant from, Instant until) throws Exception {
        String code = "C" + uid();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("type", type);
        payload.put("value", value);
        payload.put("minOrderAmount", minOrder);
        payload.put("maxDiscountAmount", maxDiscount);
        payload.put("totalQuantity", quantity);
        payload.put("validFrom", from.toString());
        payload.put("validUntil", until.toString());
        postJson("/api/coupons", payload).andReturn();
        return code;
    }

    protected JsonNode coupon(String code) throws Exception {
        return body(mvc.perform(get("/api/coupons/" + code)));
    }

    protected static Map<String, Object> orderBody(String couponCode, long... productAndQty) {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < productAndQty.length; i += 2) {
            items.add(Map.of("productId", productAndQty[i], "quantity", productAndQty[i + 1]));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        if (couponCode != null) {
            payload.put("couponCode", couponCode);
        }
        return payload;
    }

    protected ResultActions placeOrder(String user, String key, String couponCode, long... productAndQty)
            throws Exception {
        return postJson("/api/orders", orderBody(couponCode, productAndQty), "X-User-Id", user, "Idempotency-Key", key);
    }

    /** 주문을 만들고 201을 확인한 뒤 응답 본문을 돌려준다. */
    protected JsonNode newOrder(String user, String couponCode, long... productAndQty) throws Exception {
        MvcResult res = placeOrder(user, "k-" + uid(), couponCode, productAndQty).andReturn();
        if (res.getResponse().getStatus() != 201) {
            throw new AssertionError("order creation failed: " + res.getResponse().getStatus() + " "
                    + res.getResponse().getContentAsString());
        }
        return json.readTree(res.getResponse().getContentAsString());
    }

    protected ResultActions pay(long orderId, String key, String cardToken) throws Exception {
        return postJson("/api/orders/" + orderId + "/pay", Map.of("cardToken", cardToken), "Idempotency-Key", key);
    }

    protected JsonNode order(long id) throws Exception {
        return body(mvc.perform(get("/api/orders/" + id)));
    }

    protected ResultActions action(long orderId, String verb) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/" + verb));
    }
}
