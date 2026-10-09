package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final FakePaymentGateway PG;

    static {
        POSTGRES.start();
        PG = FakePaymentGateway.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // application.yml의 ${PAYMENT_GATEWAY_URL} 자리로 들어간다 (C4)
        registry.add("PAYMENT_GATEWAY_URL", PG::baseUrl);
    }

    @Autowired
    protected TestRestTemplate rest;

    @BeforeEach
    void resetGateway() {
        PG.setRefundMode(FakePaymentGateway.RefundMode.OK);
    }

    // ---------- HTTP ----------

    protected ResponseEntity<JsonNode> post(String path, Object body, String... headerPairs) {
        return exchange(HttpMethod.POST, path, body, headerPairs);
    }

    protected ResponseEntity<JsonNode> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body, String... headerPairs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.set(headerPairs[i], headerPairs[i + 1]);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    // ---------- 상품 ----------

    protected long createProduct(long price, int stock) {
        ResponseEntity<JsonNode> response = post("/api/products",
                map("name", "product-" + UUID.randomUUID(), "price", price, "stock", stock));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody().get("id").asLong();
    }

    protected JsonNode product(long id) {
        ResponseEntity<JsonNode> response = get("/api/products/" + id);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    // ---------- 쿠폰 ----------

    protected static String newCouponCode() {
        return "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 15).toUpperCase();
    }

    /** 지금 사용 가능한 쿠폰 요청 본문. overrides로 필드를 바꾸거나(null이면 제거) 추가한다. */
    protected Map<String, Object> couponRequest(String code, Object... overrides) {
        Map<String, Object> body = map(
                "code", code,
                "type", "FIXED",
                "value", 1000,
                "minOrderAmount", 0,
                "totalQuantity", 100,
                "validFrom", OffsetDateTime.now().minusDays(1).toString(),
                "validUntil", OffsetDateTime.now().plusDays(1).toString());
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == null) {
                body.remove((String) overrides[i]);
            } else {
                body.put((String) overrides[i], overrides[i + 1]);
            }
        }
        return body;
    }

    protected String createCoupon(Object... overrides) {
        String code = newCouponCode();
        ResponseEntity<JsonNode> response = post("/api/coupons", couponRequest(code, overrides));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return code;
    }

    protected JsonNode coupon(String code) {
        ResponseEntity<JsonNode> response = get("/api/coupons/" + code);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    // ---------- 주문 ----------

    protected static String newUser() {
        return "user-" + UUID.randomUUID();
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected static Map<String, Object> item(long productId, int quantity) {
        return map("productId", productId, "quantity", quantity);
    }

    protected static Map<String, Object> orderBody(String couponCode, Map<?, ?>... items) {
        Map<String, Object> body = map("items", List.of(items));
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return body;
    }

    protected ResponseEntity<JsonNode> createOrder(String userId, String key, Object body) {
        return post("/api/orders", body, "X-User-Id", userId, "Idempotency-Key", key);
    }

    /** 주문을 만들고 201을 확인한 뒤 본문을 돌려준다. */
    protected JsonNode placeOrder(String userId, String couponCode, Map<?, ?>... items) {
        ResponseEntity<JsonNode> response = createOrder(userId, newKey(), orderBody(couponCode, items));
        assertThat(response.getStatusCode().value()).as("create order: %s", response.getBody()).isEqualTo(201);
        return response.getBody();
    }

    protected JsonNode order(long id) {
        ResponseEntity<JsonNode> response = get("/api/orders/" + id);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    protected ResponseEntity<JsonNode> pay(long orderId, String key, String cardToken) {
        return post("/api/orders/" + orderId + "/pay", map("cardToken", cardToken), "Idempotency-Key", key);
    }

    protected JsonNode paidOrder(String userId, String couponCode, Map<?, ?>... items) {
        long id = placeOrder(userId, couponCode, items).get("id").asLong();
        ResponseEntity<JsonNode> response = pay(id, newKey(), "card-ok");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    // ---------- 검증 ----------

    protected static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).as("status of %s", response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("content type %s", response.getHeaders().getContentType()).isTrue();
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.hasNonNull("type")).isTrue();
        assertThat(body.hasNonNull("title")).isTrue();
        assertThat(body.hasNonNull("detail")).isTrue();
    }

    protected static Instant instant(JsonNode node, String field) {
        return OffsetDateTime.parse(node.get(field).asText()).toInstant();
    }

    // ---------- 유틸 ----------

    protected static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** n개의 작업을 동시에 시작해 결과를 모은다. */
    protected static <T> List<T> concurrently(int n, IntFunction<T> task) {
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.apply(index);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }

    protected static void sleepUntil(Instant deadline) {
        long millis = java.time.Duration.between(Instant.now(), deadline).toMillis();
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
