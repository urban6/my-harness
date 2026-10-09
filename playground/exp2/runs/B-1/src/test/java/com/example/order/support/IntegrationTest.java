package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 실제 PostgreSQL(Testcontainers)과 가짜 PG 서버 위에서 HTTP 로 API 를 호출하는 통합 테스트 기반.
 * 컨테이너·PG 서버는 JVM 당 하나를 공유하므로, 테스트는 고유한 사용자·쿠폰 코드·상품으로 서로를 격리한다.
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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        registry.add("payment.gateway.url", PG::url);
    }

    @LocalServerPort
    int port;

    protected ApiClient api;

    @BeforeEach
    void setUpIntegration() {
        api = new ApiClient("http://localhost:" + port);
        PG.reset();
    }

    // ---- 고유 식별자 -------------------------------------------------------

    protected static String uniqueUser() {
        return "user-" + UUID.randomUUID().toString().substring(0, 12);
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

    // ---- 상품 ---------------------------------------------------------------

    protected long createProduct(long price, int stock) {
        ApiResponse res = api.post("/api/products", Map.of("name", "product", "price", price, "stock", stock));
        assertThat(res.status()).as(res.toString()).isEqualTo(201);
        return res.id();
    }

    protected JsonNode product(long id) {
        ApiResponse res = api.get("/api/products/" + id);
        assertThat(res.status()).as(res.toString()).isEqualTo(200);
        return res.body();
    }

    protected void assertProduct(long id, int stock, int reserved) {
        JsonNode p = product(id);
        assertThat(p.get("stock").asInt()).as("stock of %d", id).isEqualTo(stock);
        assertThat(p.get("reserved").asInt()).as("reserved of %d", id).isEqualTo(reserved);
        assertThat(p.get("available").asInt()).as("available of %d", id).isEqualTo(stock - reserved);
    }

    // ---- 쿠폰 ---------------------------------------------------------------

    /** 현재 유효한 FIXED 1,000원 쿠폰(수량 100)을 기본값으로 하고, overrides 로 필드를 덮어쓴다. */
    protected static Map<String, Object> couponBody(String code, Map<String, Object> overrides) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("type", "FIXED");
        body.put("value", 1000);
        body.put("minOrderAmount", 0);
        body.put("maxDiscountAmount", null);
        body.put("totalQuantity", 100);
        body.put("validFrom", now.minusHours(1).toString());
        body.put("validUntil", now.plusHours(1).toString());
        body.putAll(overrides);
        return body;
    }

    protected String createCoupon(Map<String, Object> overrides) {
        String code = uniqueCouponCode();
        ApiResponse res = api.post("/api/coupons", couponBody(code, overrides));
        assertThat(res.status()).as(res.toString()).isEqualTo(201);
        return code;
    }

    protected JsonNode coupon(String code) {
        ApiResponse res = api.get("/api/coupons/" + code);
        assertThat(res.status()).as(res.toString()).isEqualTo(200);
        return res.body();
    }

    protected int usedCount(String code) {
        return coupon(code).get("usedCount").asInt();
    }

    // ---- 주문 ---------------------------------------------------------------

    protected static Map<String, Object> item(long productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    protected static Map<String, Object> orderBody(String couponCode, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("couponCode", couponCode);
        return body;
    }

    protected ApiResponse placeOrder(String userId, String key, Object body) {
        return api.post("/api/orders", body, "X-User-Id", userId, "Idempotency-Key", key);
    }

    protected ApiResponse placeOrder(String userId, String couponCode, List<Map<String, Object>> items) {
        return placeOrder(userId, uniqueKey(), orderBody(couponCode, items));
    }

    /** 결제 대기 주문을 하나 만든다. */
    protected long createOrder(String userId, String couponCode, List<Map<String, Object>> items) {
        ApiResponse res = placeOrder(userId, couponCode, items);
        assertThat(res.status()).as(res.toString()).isEqualTo(201);
        return res.id();
    }

    protected JsonNode order(long id) {
        ApiResponse res = api.get("/api/orders/" + id);
        assertThat(res.status()).as(res.toString()).isEqualTo(200);
        return res.body();
    }

    protected String orderStatus(long id) {
        return order(id).get("status").asText();
    }

    protected ApiResponse pay(long orderId, String key, String cardToken) {
        return api.post("/api/orders/" + orderId + "/pay", Map.of("cardToken", cardToken), "Idempotency-Key", key);
    }

    protected ApiResponse pay(long orderId) {
        return pay(orderId, uniqueKey(), "tok_visa");
    }

    protected void payOk(long orderId) {
        ApiResponse res = pay(orderId);
        assertThat(res.status()).as(res.toString()).isEqualTo(200);
    }

    protected ApiResponse action(long orderId, String action) {
        return api.post("/api/orders/" + orderId + "/" + action, null);
    }

    // ---- 단정 ---------------------------------------------------------------

    /** R11: RFC 9457 Problem Details 형식과 code 를 확인한다. */
    protected static void assertProblem(ApiResponse res, int status, String code) {
        assertThat(res.status()).as(res.toString()).isEqualTo(status);
        assertThat(res.contentType()).startsWith("application/problem+json");
        JsonNode body = res.body();
        assertThat(body.hasNonNull("type")).as("type").isTrue();
        assertThat(body.hasNonNull("title")).as("title").isTrue();
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.hasNonNull("detail")).as("detail").isTrue();
        assertThat(body.get("code").asText()).isEqualTo(code);
    }

    // ---- 동시 실행 ----------------------------------------------------------

    /** 모든 작업을 최대한 같은 순간에 출발시키고 결과를 순서대로 모은다. */
    protected static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
