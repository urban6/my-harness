package com.example.order.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Thin helper over MockMvc so test bodies read as API scenarios. */
public class ApiClient {

    private final MockMvc mvc;
    private final ObjectMapper om;

    public ApiClient(MockMvc mvc, ObjectMapper om) {
        this.mvc = mvc;
        this.om = om;
    }

    public ObjectMapper objectMapper() {
        return om;
    }

    // ---------- products / coupons ----------

    public ResultActions postProduct(Object body) throws Exception {
        return mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    public long createProduct(String name, long price, int stock) throws Exception {
        ObjectNode body = om.createObjectNode().put("name", name).put("price", price).put("stock", stock);
        return json(postProduct(body).andReturn()).get("id").asLong();
    }

    public ObjectNode couponBody(String code, String type, long value, Long minOrder, Long maxDiscount, int total,
                                 Instant from, Instant until) {
        ObjectNode body = om.createObjectNode().put("code", code).put("type", type).put("value", value)
                .put("totalQuantity", total).put("validFrom", from.toString()).put("validUntil", until.toString());
        if (minOrder != null) {
            body.put("minOrderAmount", minOrder);
        }
        if (maxDiscount != null) {
            body.put("maxDiscountAmount", maxDiscount);
        }
        return body;
    }

    public ResultActions postCoupon(Object body) throws Exception {
        return mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    /** Coupon valid from one hour ago to one day ahead (relative to real time; tests use a real-time-anchored clock). */
    public void createCoupon(String code, String type, long value, Long minOrder, Long maxDiscount, int total) throws Exception {
        Instant now = Instant.now();
        postCoupon(couponBody(code, type, value, minOrder, maxDiscount, total, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(1)))).andReturn();
    }

    // ---------- orders ----------

    public ObjectNode orderBody(String couponCode, long... productIdAndQuantityPairs) {
        ObjectNode body = om.createObjectNode();
        ArrayNode items = body.putArray("items");
        for (int i = 0; i < productIdAndQuantityPairs.length; i += 2) {
            items.addObject().put("productId", productIdAndQuantityPairs[i]).put("quantity", (int) productIdAndQuantityPairs[i + 1]);
        }
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return body;
    }

    public ResultActions postOrder(String userId, String key, Object body) throws Exception {
        var req = post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        if (userId != null) {
            req.header("X-User-Id", userId);
        }
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    /** Creates an order that is expected to succeed (201) and returns its JSON. */
    public JsonNode createOrder(String userId, String key, String couponCode, long... productIdAndQuantityPairs) throws Exception {
        MvcResult result = postOrder(userId, key, orderBody(couponCode, productIdAndQuantityPairs)).andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("expected 201 but got " + result.getResponse().getStatus() + ": "
                    + result.getResponse().getContentAsString());
        }
        return json(result);
    }

    public void getProductAvailable(long productId, int expectedAvailable) throws Exception {
        mvc.perform(get("/api/products/" + productId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.available").value(expectedAvailable));
    }

    public ResultActions getOrder(long id) throws Exception {
        return mvc.perform(get("/api/orders/" + id));
    }

    public ResultActions listOrders(String query) throws Exception {
        return mvc.perform(get("/api/orders" + (query == null || query.isEmpty() ? "" : "?" + query)));
    }

    public ResultActions pay(long orderId, String key, String cardToken) throws Exception {
        var req = post("/api/orders/" + orderId + "/pay").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(om.createObjectNode().put("cardToken", cardToken)));
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    public ResultActions action(long orderId, String action) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/" + action));
    }

    /** Create order then pay it successfully; returns the order id. */
    public long createPaidOrder(String userId, String key, String couponCode, long... pairs) throws Exception {
        long id = createOrder(userId, key, couponCode, pairs).get("id").asLong();
        MvcResult paid = pay(id, "pay-" + key, "tok_test").andReturn();
        if (paid.getResponse().getStatus() != 200) {
            throw new AssertionError("pay failed: " + paid.getResponse().getContentAsString());
        }
        return id;
    }

    public JsonNode json(MvcResult result) throws Exception {
        return om.readTree(result.getResponse().getContentAsString());
    }
}
