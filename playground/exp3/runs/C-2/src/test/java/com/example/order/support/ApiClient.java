package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Thin HTTP/domain helper around TestRestTemplate (never throws on 4xx/5xx). */
public final class ApiClient {

    private final TestRestTemplate rest;

    public ApiClient(TestRestTemplate rest) {
        this.rest = rest;
    }

    public static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    public static Map<String, Object> item(long productId, long quantity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("quantity", quantity);
        return m;
    }

    // ---- raw HTTP ----

    /** headers = alternating name, value. */
    public ResponseEntity<JsonNode> post(String path, Object body, String... headers) {
        return exchange(HttpMethod.POST, path, body, MediaType.APPLICATION_JSON, headers);
    }

    public ResponseEntity<JsonNode> get(String path) {
        return exchange(HttpMethod.GET, path, null, null);
    }

    public ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body, MediaType contentType,
            String... headers) {
        HttpHeaders h = new HttpHeaders();
        if (contentType != null) {
            h.setContentType(contentType);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            h.add(headers[i], headers[i + 1]);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    // ---- products ----

    public ResponseEntity<JsonNode> createProduct(String name, long price, long stock) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("price", price);
        body.put("stock", stock);
        return post("/api/products", body);
    }

    public long newProduct(long price, long stock) {
        return createProduct(uniq("prod"), price, stock).getBody().get("id").asLong();
    }

    public JsonNode product(long id) {
        return get("/api/products/" + id).getBody();
    }

    // ---- coupons ----

    public Map<String, Object> couponBody(String code, String type, long value, Long min, Long max, long total,
            String validFrom, String validUntil) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("type", type);
        m.put("value", value);
        m.put("minOrderAmount", min);
        m.put("maxDiscountAmount", max);
        m.put("totalQuantity", total);
        m.put("validFrom", validFrom);
        m.put("validUntil", validUntil);
        return m;
    }

    /** Creates a currently-valid coupon and returns its (unique) code. */
    public String newCoupon(String type, long value, Long min, Long max, long total) {
        String code = uniq("CP");
        ResponseEntity<JsonNode> res = post("/api/coupons",
                couponBody(code, type, value, min, max, total, "2020-01-01T00:00:00Z", "2099-01-01T00:00:00Z"));
        if (res.getStatusCode().value() != 201) {
            throw new IllegalStateException("coupon create failed: " + res);
        }
        return code;
    }

    public JsonNode coupon(String code) {
        return get("/api/coupons/" + code).getBody();
    }

    // ---- orders ----

    @SafeVarargs
    public final ResponseEntity<JsonNode> placeOrder(String userId, String key, String couponCode,
            Map<String, Object>... items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(items));
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return post("/api/orders", body, "X-User-Id", userId, "Idempotency-Key", key);
    }

    /** Creates a PENDING_PAYMENT order with fresh user/key and asserts 201. */
    public JsonNode orderOk(long productId, long quantity) {
        return orderOk(uniq("user"), productId, quantity, null);
    }

    public JsonNode orderOk(String userId, long productId, long quantity, String couponCode) {
        ResponseEntity<JsonNode> res = placeOrder(userId, uniq("ok"), couponCode, item(productId, quantity));
        if (res.getStatusCode().value() != 201) {
            throw new IllegalStateException("order create failed: " + res);
        }
        return res.getBody();
    }

    public JsonNode order(long id) {
        return get("/api/orders/" + id).getBody();
    }

    public ResponseEntity<JsonNode> pay(long orderId, String key, String cardToken) {
        return post("/api/orders/" + orderId + "/pay", Map.of("cardToken", cardToken), "Idempotency-Key", key);
    }

    public ResponseEntity<JsonNode> payOk(long orderId) {
        return pay(orderId, uniq("pay"), "tok_ok");
    }

    public ResponseEntity<JsonNode> cancel(long orderId) {
        return post("/api/orders/" + orderId + "/cancel", null);
    }

    public ResponseEntity<JsonNode> ship(long orderId) {
        return post("/api/orders/" + orderId + "/ship", null);
    }

    public ResponseEntity<JsonNode> deliver(long orderId) {
        return post("/api/orders/" + orderId + "/deliver", null);
    }

    /**
     * Builds an order (own product, price 1000, stock 10, qty 1) in the requested status. Needs a PG stub with the
     * default behaviour (tok_ok approves, tok_decline declines, refund ok).
     */
    public long orderInState(String status) {
        long productId = newProduct(1000, 10);
        long id = orderOk(productId, 1).get("id").asLong();
        switch (status) {
            case "PENDING_PAYMENT" -> { }
            case "PAID" -> payOk(id);
            case "SHIPPED" -> { payOk(id); ship(id); }
            case "DELIVERED" -> { payOk(id); ship(id); deliver(id); }
            case "CANCELLED" -> cancel(id);
            case "REFUNDED" -> { payOk(id); cancel(id); }
            case "PAYMENT_FAILED" -> pay(id, uniq("pay"), "tok_decline");
            default -> throw new IllegalArgumentException(status);
        }
        String actual = order(id).get("status").asText();
        if (!actual.equals(status)) {
            throw new IllegalStateException("fixture wanted " + status + " but got " + actual);
        }
        return id;
    }

    public static List<String> fieldsOf(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        problem.path("errors").forEach(e -> fields.add(e.path("field").asText()));
        return fields;
    }
}
