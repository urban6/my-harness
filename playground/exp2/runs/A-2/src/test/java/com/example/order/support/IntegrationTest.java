package com.example.order.support;

import com.example.order.support.Api.Resp;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 PostgreSQL(Testcontainers)과 가짜 PG 서버에 붙여 HTTP로 검증하는 통합 테스트 기반.
 * 컨테이너와 PG 서버는 JVM 전체에서 하나만 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final FakePaymentGateway PG = new FakePaymentGateway();

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

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Api api;

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE order_item, orders, coupon, product, idempotency_record RESTART IDENTITY CASCADE");
        PG.reset();
        api = new Api(port);
    }

    // ------------------------------------------------------------ 상품·쿠폰

    protected long createProduct(long price, int stock) {
        Resp resp = api.post("/api/products", Map.of("name", "product-" + price, "price", price, "stock", stock));
        assertThat(resp.status()).as(resp.raw()).isEqualTo(201);
        return resp.id();
    }

    protected JsonNode product(long id) {
        Resp resp = api.get("/api/products/" + id);
        assertThat(resp.status()).isEqualTo(200);
        return resp.json();
    }

    /** 지금 유효한 쿠폰 요청 본문. 필요한 필드만 덮어써서 쓴다. */
    protected static Map<String, Object> couponBody(String code, String type, long value) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("type", type);
        body.put("value", value);
        body.put("minOrderAmount", 0);
        body.put("maxDiscountAmount", null);
        body.put("totalQuantity", 100);
        body.put("validFrom", now.minusDays(1).toString());
        body.put("validUntil", now.plusDays(1).toString());
        return body;
    }

    protected void createCoupon(Map<String, Object> body) {
        Resp resp = api.post("/api/coupons", body);
        assertThat(resp.status()).as(resp.raw()).isEqualTo(201);
    }

    protected JsonNode coupon(String code) {
        Resp resp = api.get("/api/coupons/" + code);
        assertThat(resp.status()).isEqualTo(200);
        return resp.json();
    }

    // ------------------------------------------------------------ 주문

    /** productIdAndQuantity: productId, quantity, productId, quantity ... */
    protected static Map<String, Object> orderBody(String couponCode, long... productIdAndQuantity) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < productIdAndQuantity.length; i += 2) {
            items.add(Map.of("productId", productIdAndQuantity[i], "quantity", productIdAndQuantity[i + 1]));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return body;
    }

    protected Resp createOrder(String userId, String key, Object body) {
        return api.post("/api/orders", body, "X-User-Id", userId, "Idempotency-Key", key);
    }

    protected Resp placeOrder(String userId, String couponCode, long... productIdAndQuantity) {
        return createOrder(userId, newKey(), orderBody(couponCode, productIdAndQuantity));
    }

    protected long placeOrderOk(String userId, String couponCode, long... productIdAndQuantity) {
        Resp resp = placeOrder(userId, couponCode, productIdAndQuantity);
        assertThat(resp.status()).as(resp.raw()).isEqualTo(201);
        return resp.id();
    }

    protected JsonNode order(long id) {
        Resp resp = api.get("/api/orders/" + id);
        assertThat(resp.status()).isEqualTo(200);
        return resp.json();
    }

    protected Resp pay(long orderId, String key, String cardToken) {
        return api.post("/api/orders/" + orderId + "/pay", Map.of("cardToken", cardToken), "Idempotency-Key", key);
    }

    protected Resp pay(long orderId) {
        return pay(orderId, newKey(), "card-ok");
    }

    protected long paidOrder(String userId, String couponCode, long... productIdAndQuantity) {
        long id = placeOrderOk(userId, couponCode, productIdAndQuantity);
        Resp resp = pay(id);
        assertThat(resp.status()).as(resp.raw()).isEqualTo(200);
        return id;
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------ 검증

    /** R11: 모든 오류는 RFC 9457 Problem Details. */
    protected static void assertProblem(Resp resp, int status, String code) {
        assertThat(resp.status()).as(resp.raw()).isEqualTo(status);
        assertThat(resp.header("Content-Type")).startsWith("application/problem+json");
        assertThat(resp.json().path("status").asInt()).isEqualTo(status);
        assertThat(resp.json().path("code").asText()).isEqualTo(code);
        assertThat(resp.json().hasNonNull("type")).isTrue();
        assertThat(resp.json().hasNonNull("title")).isTrue();
        assertThat(resp.json().hasNonNull("detail")).isTrue();
    }
}
