package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 실제 HTTP로 API를 호출하는 테스트 헬퍼. */
public final class TestApi {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;

    public TestApi(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public record Response(int status, java.net.http.HttpHeaders headers, String rawBody, JsonNode body) {

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public long id() {
            return body.get("id").asLong();
        }

        public Response assertStatus(int expected) {
            assertThat(status).as("status, body=%s", rawBody).isEqualTo(expected);
            return this;
        }

        /** RFC 9457 Problem Details 형태와 code를 확인한다(R11). */
        public Response assertProblem(int expectedStatus, String expectedCode) {
            assertStatus(expectedStatus);
            assertThat(header("Content-Type")).startsWith("application/problem+json");
            assertThat(body.hasNonNull("type")).as("type").isTrue();
            assertThat(body.hasNonNull("title")).as("title").isTrue();
            assertThat(body.get("status").asInt()).isEqualTo(expectedStatus);
            assertThat(body.hasNonNull("detail")).as("detail").isTrue();
            assertThat(body.get("code").asText()).isEqualTo(expectedCode);
            return this;
        }
    }

    // ---- 저수준 호출 ----

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET(), Map.of());
    }

    public Response post(String path, Object body, Map<String, String> headers) {
        String json = body instanceof String s ? s : write(body);
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), headers);
    }

    public Response post(String path, Object body) {
        return post(path, body, Map.of());
    }

    private Response send(HttpRequest.Builder builder, Map<String, String> headers) {
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response = CLIENT.send(builder.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode node = raw == null || raw.isBlank() ? JSON.nullNode() : JSON.readTree(raw);
            return new Response(response.statusCode(), response.headers(), raw, node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String write(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 도메인 헬퍼 ----

    public long createProduct(long price, int stock) {
        return post("/api/products", Map.of("name", "상품-" + UUID.randomUUID(), "price", price, "stock", stock))
                .assertStatus(201).id();
    }

    public JsonNode product(long id) {
        return get("/api/products/" + id).assertStatus(200).body();
    }

    public static Map<String, Object> coupon(String code, String type, long value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("type", type);
        body.put("value", value);
        body.put("totalQuantity", 100);
        body.put("validFrom", OffsetDateTime.now().minusDays(1).toString());
        body.put("validUntil", OffsetDateTime.now().plusDays(1).toString());
        return body;
    }

    public String createCoupon(Map<String, Object> body) {
        return post("/api/coupons", body).assertStatus(201).body().get("code").asText();
    }

    public String createCoupon(String type, long value) {
        return createCoupon(coupon(newCouponCode(), type, value));
    }

    public JsonNode coupon(String code) {
        return get("/api/coupons/" + code).assertStatus(200).body();
    }

    public static Map<String, Object> item(long productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    public static Map<String, Object> orderBody(String couponCode, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return body;
    }

    public Response createOrder(String userId, String key, Object body) {
        return post("/api/orders", body, Map.of("X-User-Id", userId, "Idempotency-Key", key));
    }

    public Response createOrder(String userId, String couponCode, List<Map<String, Object>> items) {
        return createOrder(userId, newKey(), orderBody(couponCode, items));
    }

    public long createOrderId(String userId, long productId, int quantity) {
        return createOrder(userId, null, List.of(item(productId, quantity))).assertStatus(201).id();
    }

    public JsonNode order(long id) {
        return get("/api/orders/" + id).assertStatus(200).body();
    }

    public Response pay(long orderId, String key, String cardToken) {
        return post("/api/orders/" + orderId + "/pay", Map.of("cardToken", cardToken), Map.of("Idempotency-Key", key));
    }

    public Response pay(long orderId) {
        return pay(orderId, newKey(), "tok_visa");
    }

    public Response cancel(long orderId) {
        return post("/api/orders/" + orderId + "/cancel", "");
    }

    public Response ship(long orderId) {
        return post("/api/orders/" + orderId + "/ship", "");
    }

    public Response deliver(long orderId) {
        return post("/api/orders/" + orderId + "/deliver", "");
    }

    // ---- 식별자 생성 ----

    public static String newUserId() {
        return "user-" + UUID.randomUUID();
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    public static String newCouponCode() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder("CP");
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public static List<Map<String, Object>> items(Map<String, Object>... items) {
        return new ArrayList<>(List.of(items));
    }
}
