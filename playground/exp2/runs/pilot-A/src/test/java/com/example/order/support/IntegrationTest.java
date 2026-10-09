package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api.Response;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 실제 PostgreSQL(Testcontainers)과 가짜 PG를 띄우고 HTTP로 API를 검증하는 테스트의 기반 클래스. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final FakePaymentGateway PG = new FakePaymentGateway();
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payment.gateway.url", PG::baseUrl);
    }

    @LocalServerPort
    int port;

    protected Api api;

    @BeforeEach
    void setUpApi() {
        api = new Api(port);
    }

    // ---- fixtures ----

    protected static String uniqueUser() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String uniqueKey() {
        return UUID.randomUUID().toString();
    }

    protected static String uniqueCouponCode() {
        return "C" + SEQ.incrementAndGet() + UUID.randomUUID().toString().replace("-", "").substring(0, 6)
                .toUpperCase();
    }

    protected long createProduct(long price, long stock) {
        Response r = api.post("/api/products",
                Api.json(Map.of("name", "product-" + SEQ.incrementAndGet(), "price", price, "stock", stock)));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    protected JsonNode product(long id) {
        Response r = api.get("/api/products/" + id);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.body();
    }

    protected Map<String, Object> couponBody(String code, String type, long value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("type", type);
        body.put("value", value);
        body.put("minOrderAmount", 0);
        body.put("totalQuantity", 100);
        body.put("validFrom", OffsetDateTime.now().minusDays(1).toString());
        body.put("validUntil", OffsetDateTime.now().plusDays(1).toString());
        return body;
    }

    protected String createCoupon(Map<String, Object> body) {
        Response r = api.post("/api/coupons", Api.json(body));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.body().get("code").asText();
    }

    protected String createCoupon(String type, long value, long totalQuantity) {
        Map<String, Object> body = couponBody(uniqueCouponCode(), type, value);
        body.put("totalQuantity", totalQuantity);
        return createCoupon(body);
    }

    protected JsonNode coupon(String code) {
        Response r = api.get("/api/coupons/" + code);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.body();
    }

    /** items: productId, quantity 를 번갈아 넘긴다. */
    protected static String orderBody(String couponCode, long... productIdAndQuantity) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < productIdAndQuantity.length; i += 2) {
            items.add(Map.of("productId", productIdAndQuantity[i], "quantity", productIdAndQuantity[i + 1]));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return Api.json(body);
    }

    protected Response postOrder(String userId, String key, String body) {
        return api.post("/api/orders", body, "X-User-Id", userId, "Idempotency-Key", key);
    }

    protected JsonNode createOrder(String userId, String couponCode, long... productIdAndQuantity) {
        Response r = postOrder(userId, uniqueKey(), orderBody(couponCode, productIdAndQuantity));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.body();
    }

    protected Response pay(long orderId, String key, String cardToken) {
        return api.post("/api/orders/" + orderId + "/pay", Api.json(Map.of("cardToken", cardToken)),
                "Idempotency-Key", key);
    }

    protected JsonNode paidOrder(String userId, String couponCode, String cardToken, long... productIdAndQuantity) {
        long orderId = createOrder(userId, couponCode, productIdAndQuantity).get("id").asLong();
        Response r = pay(orderId, uniqueKey(), cardToken);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.body();
    }

    protected JsonNode order(long id) {
        Response r = api.get("/api/orders/" + id);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.body();
    }

    protected static void assertProblem(Response r, int status, String code) {
        assertThat(r.status()).as(r.toString()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.code()).as(r.toString()).isEqualTo(code);
    }
}
