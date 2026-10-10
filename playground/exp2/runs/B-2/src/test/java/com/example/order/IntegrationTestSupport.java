package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 PostgreSQL(Testcontainers)과 가짜 PG 서버를 띄워 HTTP로 API를 검증하는 통합 테스트 기반.
 * 컨테이너와 PG 서버는 모든 테스트 클래스·컨텍스트가 공유한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final FakePaymentGateway PG = new FakePaymentGateway();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("PAYMENT_GATEWAY_URL", PG::baseUrl);
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void resetGateway() {
        PG.reset();
    }

    // ---------- HTTP ----------

    public record Res(int status, HttpHeaders headers, JsonNode body) {

        public String code() {
            return body.path("code").asText(null);
        }
    }

    protected Res post(String path, Object body, Map<String, String> headers) {
        return exchange(HttpMethod.POST, path, body instanceof String s ? s : toJson(body), headers);
    }

    protected Res post(String path) {
        return exchange(HttpMethod.POST, path, null, Map.of());
    }

    protected Res get(String path) {
        return exchange(HttpMethod.GET, path, null, Map.of());
    }

    private Res exchange(HttpMethod method, String path, String json, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        if (json != null) {
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        }
        headers.forEach(httpHeaders::set);
        ResponseEntity<String> response = rest.exchange(path, method, new HttpEntity<>(json, httpHeaders), String.class);
        try {
            JsonNode body = response.getBody() == null ? objectMapper.nullNode() : objectMapper.readTree(response.getBody());
            return new Res(response.getStatusCode().value(), response.getHeaders(), body);
        } catch (Exception e) {
            throw new IllegalStateException("응답 JSON을 읽지 못했습니다: " + response.getBody(), e);
        }
    }

    protected String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** R11: RFC 9457 Problem Details 형식과 상태·code를 함께 검증한다. */
    protected static void assertProblem(Res res, int status, String code) {
        assertThat(res.status()).as("status, body=%s", res.body()).isEqualTo(status);
        assertThat(res.headers().getContentType()).isNotNull();
        assertThat(res.headers().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("content-type=%s", res.headers().getContentType()).isTrue();
        assertThat(res.body().hasNonNull("type")).isTrue();
        assertThat(res.body().hasNonNull("title")).isTrue();
        assertThat(res.body().path("status").asInt()).isEqualTo(status);
        assertThat(res.body().hasNonNull("detail")).isTrue();
        assertThat(res.code()).isEqualTo(code);
    }

    // ---------- 도메인 헬퍼 ----------

    protected static String uniqueUser() {
        return "user-" + UUID.randomUUID();
    }

    protected static String uniqueKey() {
        return UUID.randomUUID().toString();
    }

    protected static String uniqueCouponCode() {
        return "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 15).toUpperCase();
    }

    protected long createProduct(long price, int stock) {
        Res res = post("/api/products", Map.of("name", "상품", "price", price, "stock", stock), Map.of());
        assertThat(res.status()).as("create product: %s", res.body()).isEqualTo(201);
        return res.body().get("id").asLong();
    }

    /** 기본값(정액 1,000원, 100장, 지금 유효)에 overrides를 덮어 쿠폰 요청 본문을 만든다. */
    protected Map<String, Object> couponBody(Map<String, Object> overrides) {
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", uniqueCouponCode());
        body.put("type", "FIXED");
        body.put("value", 1_000);
        body.put("minOrderAmount", 0);
        body.put("maxDiscountAmount", null);
        body.put("totalQuantity", 100);
        body.put("validFrom", now.minus(1, ChronoUnit.HOURS).toString());
        body.put("validUntil", now.plus(1, ChronoUnit.HOURS).toString());
        body.putAll(overrides);
        return body;
    }

    protected String createCoupon(Map<String, Object> overrides) {
        Map<String, Object> body = couponBody(overrides);
        Res res = post("/api/coupons", body, Map.of());
        assertThat(res.status()).as("create coupon: %s", res.body()).isEqualTo(201);
        return (String) body.get("code");
    }

    /** items: {productId, quantity} 쌍. */
    protected static Map<String, Object> orderBody(String couponCode, long[]... items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (long[] item : items) {
            list.add(Map.of("productId", item[0], "quantity", item[1]));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", list);
        body.put("couponCode", couponCode);
        return body;
    }

    protected static long[] item(long productId, long quantity) {
        return new long[] {productId, quantity};
    }

    protected Res postOrder(String userId, String key, Object body) {
        return post("/api/orders", body, Map.of("X-User-Id", userId, "Idempotency-Key", key));
    }

    protected Res createOrder(String userId, String couponCode, long[]... items) {
        return postOrder(userId, uniqueKey(), orderBody(couponCode, items));
    }

    protected long placeOrder(String userId, String couponCode, long[]... items) {
        Res res = createOrder(userId, couponCode, items);
        assertThat(res.status()).as("create order: %s", res.body()).isEqualTo(201);
        return res.body().get("id").asLong();
    }

    protected Res pay(long orderId, String key) {
        return post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok_test"), Map.of("Idempotency-Key", key));
    }

    protected long paidOrder(String userId, String couponCode, long[]... items) {
        long orderId = placeOrder(userId, couponCode, items);
        Res res = pay(orderId, uniqueKey());
        assertThat(res.status()).as("pay: %s", res.body()).isEqualTo(200);
        return orderId;
    }

    protected JsonNode product(long id) {
        return get("/api/products/" + id).body();
    }

    protected JsonNode coupon(String code) {
        return get("/api/coupons/" + code).body();
    }

    protected JsonNode order(long id) {
        return get("/api/orders/" + id).body();
    }

    /** 모든 작업을 동시에 출발시키고 결과를 모은다. */
    protected static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
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
