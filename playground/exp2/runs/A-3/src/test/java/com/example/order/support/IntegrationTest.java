package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 HTTP 로 애플리케이션을 호출하는 통합 테스트 기반. PostgreSQL 컨테이너와 가짜 PG 는 모든 테스트가 공유한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payment.gateway.url", () -> FakePaymentGateway.get().url());
    }

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    protected int port;

    protected final FakePaymentGateway pg = FakePaymentGateway.get();

    public record Resp(int status, HttpHeaders headers, String raw, JsonNode json) {

        public String code() {
            return json.path("code").asText(null);
        }

        public long id() {
            return json.path("id").asLong();
        }

        public String contentType() {
            return headers.firstValue("Content-Type").orElse("");
        }
    }

    // ---------- HTTP ----------

    protected Resp get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET(), Map.of());
    }

    protected Resp post(String path, Object body) {
        return post(path, body, Map.of());
    }

    protected Resp post(String path, Object body, Map<String, String> headers) {
        String raw;
        try {
            raw = body == null ? "" : body instanceof String s ? s : JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(raw));
        return send(builder, headers);
    }

    private Resp send(HttpRequest.Builder builder, Map<String, String> headers) {
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode json = raw == null || raw.isBlank() ? JSON.nullNode() : JSON.readTree(raw);
            return new Resp(response.statusCode(), response.headers(), raw, json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    // ---------- 도메인 헬퍼 ----------

    protected static String uniqueUser() {
        return "user-" + UUID.randomUUID();
    }

    protected static String uniqueKey() {
        return UUID.randomUUID().toString();
    }

    protected static String uniqueCouponCode() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder("C");
        for (int i = 0; i < 15; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    protected long createProduct(long price, int stock) {
        Resp r = post("/api/products", Map.of("name", "product-" + UUID.randomUUID(), "price", price, "stock", stock));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.id();
    }

    protected JsonNode product(long id) {
        Resp r = get("/api/products/" + id);
        assertThat(r.status()).isEqualTo(200);
        return r.json();
    }

    protected Map<String, Object> couponBody(String code, String type, long value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("type", type);
        body.put("value", value);
        body.put("totalQuantity", 100);
        body.put("validFrom", OffsetDateTime.now().minusDays(1).toString());
        body.put("validUntil", OffsetDateTime.now().plusDays(1).toString());
        return body;
    }

    protected String createCoupon(Map<String, Object> body) {
        Resp r = post("/api/coupons", body);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.json().path("code").asText();
    }

    protected String createCoupon(String type, long value) {
        return createCoupon(couponBody(uniqueCouponCode(), type, value));
    }

    protected JsonNode coupon(String code) {
        Resp r = get("/api/coupons/" + code);
        assertThat(r.status()).isEqualTo(200);
        return r.json();
    }

    public static Map<String, Object> item(long productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    protected static Map<String, Object> orderBody(String couponCode, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", new ArrayList<>(items));
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return body;
    }

    protected Resp createOrder(String userId, String key, Object body) {
        return post("/api/orders", body, Map.of("X-User-Id", userId, "Idempotency-Key", key));
    }

    protected Resp createOrder(String userId, String couponCode, List<Map<String, Object>> items) {
        return createOrder(userId, uniqueKey(), orderBody(couponCode, items));
    }

    /** 성공해야 하는 주문 생성. */
    protected JsonNode placeOrder(String userId, String couponCode, Map<String, Object>... items) {
        Resp r = createOrder(userId, couponCode, List.of(items));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.json();
    }

    protected JsonNode placeOrder(Map<String, Object>... items) {
        return placeOrder(uniqueUser(), null, items);
    }

    protected JsonNode order(long id) {
        Resp r = get("/api/orders/" + id);
        assertThat(r.status()).isEqualTo(200);
        return r.json();
    }

    protected Resp pay(long orderId, String cardToken) {
        return pay(orderId, uniqueKey(), cardToken);
    }

    protected Resp pay(long orderId, String key, String cardToken) {
        return post("/api/orders/" + orderId + "/pay", Map.of("cardToken", cardToken), Map.of("Idempotency-Key", key));
    }

    /** 결제까지 끝난 주문. */
    protected JsonNode paidOrder(String cardToken, Map<String, Object>... items) {
        JsonNode created = placeOrder(items);
        Resp paid = pay(created.path("id").asLong(), cardToken);
        assertThat(paid.status()).as(paid.raw()).isEqualTo(200);
        return paid.json();
    }

    protected static Instant instant(JsonNode node) {
        return OffsetDateTime.parse(node.asText()).toInstant();
    }

    protected static void assertProblem(Resp r, int status, String code) {
        assertThat(r.status()).as(r.raw()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.code()).isEqualTo(code);
    }

    protected static void sleepUntil(Instant deadline) {
        long millis = ChronoUnit.MILLIS.between(Instant.now(), deadline);
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
