package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 통합 테스트 공통 베이스.
 *
 * <ul>
 *   <li>PostgreSQL 컨테이너와 가짜 PG 서버는 JVM 당 싱글턴(컨텍스트 캐시 유지).</li>
 *   <li>{@code payment.gateway.url} 은 가짜 PG, {@code order.payment-ttl} 은 기본 PT15M(클래스 레벨
 *       {@code @TestPropertySource(properties = "order.payment-ttl=PT3S")} 로 서브클래스에서 덮어쓸 수 있다 —
 *       다른 컨텍스트가 하나 더 만들어질 뿐 컨테이너·가짜 PG는 공유).</li>
 *   <li>매 테스트 전에 모든 테이블 TRUNCATE + 가짜 PG reset.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "order.payment-ttl=PT15M")
public abstract class IntegrationTestBase {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    protected static final FakePaymentGateway PG = new FakePaymentGateway();

    static {
        POSTGRES.start();
        PG.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PG::stop));
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("payment.gateway.url", PG::url);
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE order_items, orders, idempotency_records, coupons, products RESTART IDENTITY CASCADE");
        PG.reset();
    }

    // ---- HTTP 헬퍼 (응답 본문은 JsonNode; 오류 응답도 예외 없이 반환) ----

    /** headers 는 name, value, name, value ... 순서. */
    protected ResponseEntity<JsonNode> post(String path, Object body, String... headers) {
        return exchange(HttpMethod.POST, path, body, headers);
    }

    protected ResponseEntity<JsonNode> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body, String... headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            h.set(headers[i], headers[i + 1]);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    // ---- 도메인 헬퍼 ----

    protected JsonNode createProduct(String name, long price, int stock) {
        ResponseEntity<JsonNode> res = post("/api/products", Map.of("name", name, "price", price, "stock", stock));
        if (res.getStatusCode().value() != 201) {
            throw new AssertionError("상품 생성 실패: " + res.getStatusCode() + " " + res.getBody());
        }
        return res.getBody();
    }

    protected ResponseEntity<JsonNode> createOrder(String userId, String idempotencyKey, Object body) {
        return post("/api/orders", body, "X-User-Id", userId, "Idempotency-Key", idempotencyKey);
    }

    protected ResponseEntity<JsonNode> payOrder(long orderId, String idempotencyKey, String cardToken) {
        return post("/api/orders/" + orderId + "/pay", Map.of("cardToken", cardToken),
                "Idempotency-Key", idempotencyKey);
    }

    // ---- 확장 헬퍼 (요구사항별 테스트 공용) ----

    protected static String key() {
        return UUID.randomUUID().toString();
    }

    /** pq 는 productId, quantity, productId, quantity ... 순서. */
    protected static Map<String, Object> orderBody(String couponCode, long... pq) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i + 1 < pq.length; i += 2) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", pq[i]);
            item.put("quantity", pq[i + 1]);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return body;
    }

    /** 새 멱등 키로 주문 생성 요청(상태코드 무관). */
    protected ResponseEntity<JsonNode> order(String userId, String couponCode, long... pq) {
        return createOrder(userId, key(), orderBody(couponCode, pq));
    }

    /** 새 멱등 키로 주문 생성, 201 이어야 하며 본문을 반환. */
    protected JsonNode orderOk(String userId, String couponCode, long... pq) {
        ResponseEntity<JsonNode> res = order(userId, couponCode, pq);
        assertThat(res.getStatusCode().value()).as("주문 생성 응답: %s", res.getBody()).isEqualTo(201);
        return res.getBody();
    }

    protected JsonNode getOk(String path) {
        ResponseEntity<JsonNode> res = get(path);
        assertThat(res.getStatusCode().value()).as("GET %s -> %s", path, res.getBody()).isEqualTo(200);
        return res.getBody();
    }

    protected JsonNode getOrder(long id) {
        return getOk("/api/orders/" + id);
    }

    protected JsonNode getProduct(long id) {
        return getOk("/api/products/" + id);
    }

    protected JsonNode getCoupon(String code) {
        return getOk("/api/coupons/" + code);
    }

    protected String statusOf(long orderId) {
        return getOrder(orderId).get("status").asText();
    }

    protected ResponseEntity<JsonNode> cancel(long orderId) {
        return post("/api/orders/" + orderId + "/cancel", null);
    }

    protected ResponseEntity<JsonNode> ship(long orderId) {
        return post("/api/orders/" + orderId + "/ship", null);
    }

    protected ResponseEntity<JsonNode> deliver(long orderId) {
        return post("/api/orders/" + orderId + "/deliver", null);
    }

    /** 새 멱등 키로 결제, 200 이어야 한다. */
    protected JsonNode payOk(long orderId) {
        ResponseEntity<JsonNode> res = payOrder(orderId, key(), "tok_ok");
        assertThat(res.getStatusCode().value()).as("결제 응답: %s", res.getBody()).isEqualTo(200);
        return res.getBody();
    }

    /** 유효한 쿠폰 요청 본문(기간: 어제 ~ 1년 뒤, minOrderAmount 0, 상한 없음, 수량 100). */
    protected static Map<String, Object> couponBody(String code, String type, long value) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("type", type);
        b.put("value", value);
        b.put("minOrderAmount", 0);
        b.put("totalQuantity", 100);
        b.put("validFrom", Instant.now().minusSeconds(86_400).toString());
        b.put("validUntil", Instant.now().plusSeconds(365L * 86_400).toString());
        return b;
    }

    protected JsonNode createCoupon(Map<String, Object> body) {
        ResponseEntity<JsonNode> res = post("/api/coupons", body);
        assertThat(res.getStatusCode().value()).as("쿠폰 생성 응답: %s", res.getBody()).isEqualTo(201);
        return res.getBody();
    }

    protected JsonNode createCoupon(String code, String type, long value) {
        return createCoupon(couponBody(code, type, value));
    }

    protected long countOrders() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
    }

    /** R11 공통 단정: 상태코드·code·problem+json·필수 필드. */
    protected static void assertProblem(ResponseEntity<JsonNode> res, int status, String code) {
        assertThat(res.getStatusCode().value()).as("응답 본문: %s", res.getBody()).isEqualTo(status);
        MediaType ct = res.getHeaders().getContentType();
        assertThat(ct).as("Content-Type").isNotNull();
        assertThat(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(ct))
                .as("Content-Type 은 application/problem+json 이어야 한다: %s", ct).isTrue();
        JsonNode b = res.getBody();
        assertThat(b).isNotNull();
        for (String f : List.of("type", "title", "status", "detail", "code")) {
            assertThat(b.hasNonNull(f)).as("problem 필드 %s 존재: %s", f, b).isTrue();
        }
        assertThat(b.get("status").asInt()).isEqualTo(status);
        assertThat(b.get("code").asText()).isEqualTo(code);
    }

    /** 조건이 참이 될 때까지 폴링(50ms). deadline 까지 참이 되지 않으면 false. */
    protected static boolean awaitUntil(Instant deadline, BooleanSupplier condition) {
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (Instant.now().isAfter(deadline)) {
                return condition.getAsBoolean();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** 모든 작업을 래치로 동시에 출발시키고 결과를 입력 순서대로 반환한다. */
    protected static List<ResponseEntity<JsonNode>> runConcurrently(List<Supplier<ResponseEntity<JsonNode>>> tasks) {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (Supplier<ResponseEntity<JsonNode>> t : tasks) {
                Callable<ResponseEntity<JsonNode>> c = () -> {
                    ready.countDown();
                    go.await();
                    return t.get();
                };
                futures.add(pool.submit(c));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("모든 스레드 준비").isTrue();
            go.countDown();
            List<ResponseEntity<JsonNode>> results = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new AssertionError("동시 요청 실행 실패", e);
        } finally {
            pool.shutdownNow();
        }
    }

    protected static long countStatus(List<ResponseEntity<JsonNode>> results, int status) {
        return results.stream().filter(r -> r.getStatusCode().value() == status).count();
    }
}
