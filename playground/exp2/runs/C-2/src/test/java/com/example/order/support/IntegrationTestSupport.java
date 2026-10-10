package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import org.awaitility.Awaitility;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 통합 테스트 공통 기반 (컨테이너 + 가짜 PG + HTTP 헬퍼).
 *
 * <ul>
 *   <li>PostgreSQL 컨테이너와 가짜 PG는 JVM 전체에서 하나만 뜬다(static 싱글톤). Flyway V1이 한 번 적용된다.</li>
 *   <li>이 클래스는 {@code @DynamicPropertySource}를 갖지 않는다. 프로퍼티 조합이 다른 컨텍스트가 필요하면
 *       이 클래스를 직접 상속해 자신의 {@code @DynamicPropertySource}를 두고, 기본 조합이면 {@link AbstractIntegrationTest}를 상속한다.</li>
 *   <li>DB를 비우지 않는다(스케줄러가 돌고 있으므로 TRUNCATE 금지). 간섭을 피하려면 {@link #uniqueUser()},
 *       {@link #uniqueKey()}, {@link #uniqueCouponCode()}, {@link #newProduct} 처럼 고유 값을 만들어 쓴다.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=300");

    /** 가짜 PG. 매 테스트 전에 reset() 된다. */
    protected static final FakePaymentGateway PG = new FakePaymentGateway();

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PG::stop));
    }

    protected static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired
    protected TestRestTemplate rest;

    @BeforeEach
    void resetFakePaymentGateway() {
        PG.reset();
    }

    // =====================================================================
    // 고유 값 생성 (테스트 간 데이터 간섭 방지)
    // =====================================================================

    protected static String uniqueUser() {
        return "user-" + UUID.randomUUID();
    }

    protected static String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    /** 영문 대문자·숫자 4~20자 (쿠폰 코드 형식). */
    protected static String uniqueCouponCode() {
        return "C" + Long.toString(SEQ.incrementAndGet(), 36).toUpperCase() + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    // =====================================================================
    // 저수준 HTTP
    // =====================================================================

    protected ApiResponse send(HttpMethod method, String path, Map<String, String> headers, String body) {
        HttpHeaders h = new HttpHeaders();
        if (body != null) {
            h.setContentType(MediaType.APPLICATION_JSON);
        }
        if (headers != null) {
            headers.forEach(h::set);
        }
        ResponseEntity<String> r = rest.exchange(path, method, new HttpEntity<>(body, h), String.class);
        JsonNode json = null;
        if (r.getBody() != null && !r.getBody().isBlank()) {
            try {
                json = JSON.readTree(r.getBody());
            } catch (Exception ignored) {
                // JSON이 아닌 본문
            }
        }
        return new ApiResponse(r.getStatusCode().value(), r.getHeaders(), r.getBody(), json);
    }

    protected ApiResponse get(String path) {
        return send(HttpMethod.GET, path, null, null);
    }

    protected ApiResponse post(String path, Map<String, String> headers, String body) {
        return send(HttpMethod.POST, path, headers, body);
    }

    protected static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // =====================================================================
    // 상품 / 쿠폰
    // =====================================================================

    protected ApiResponse postProduct(String rawJson) {
        return post("/api/products", null, rawJson);
    }

    protected ApiResponse createProduct(String name, long price, int stock) {
        return postProduct("{\"name\":\"" + name + "\",\"price\":" + price + ",\"stock\":" + stock + "}");
    }

    /** 상품을 만들고(201 확인) id를 돌려준다. */
    protected long newProduct(long price, int stock) {
        ApiResponse r = createProduct("p-" + UUID.randomUUID().toString().substring(0, 8), price, stock);
        assertThat(r.status()).as("create product: %s", r).isEqualTo(201);
        return r.id();
    }

    protected ApiResponse getProduct(long id) {
        return get("/api/products/" + id);
    }

    protected ApiResponse postCoupon(String rawJson) {
        return post("/api/coupons", null, rawJson);
    }

    /** 현재 시각 전후 1시간이 유효기간인 쿠폰 JSON. maxDiscountAmount가 null이면 생략(제한 없음). */
    protected static String couponJson(String code, String type, long value, long minOrderAmount, Long maxDiscountAmount,
            long totalQuantity) {
        return couponJson(code, type, value, minOrderAmount, maxDiscountAmount, totalQuantity,
                Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)));
    }

    protected static String couponJson(String code, String type, long value, long minOrderAmount, Long maxDiscountAmount,
            long totalQuantity, Instant validFrom, Instant validUntil) {
        return "{\"code\":\"" + code + "\",\"type\":\"" + type + "\",\"value\":" + value
                + ",\"minOrderAmount\":" + minOrderAmount
                + (maxDiscountAmount == null ? "" : ",\"maxDiscountAmount\":" + maxDiscountAmount)
                + ",\"totalQuantity\":" + totalQuantity
                + ",\"validFrom\":\"" + validFrom + "\",\"validUntil\":\"" + validUntil + "\"}";
    }

    /** 쿠폰을 만들고(201 확인) code를 돌려준다. */
    protected String newCoupon(String type, long value, long minOrderAmount, Long maxDiscountAmount, long totalQuantity) {
        String code = uniqueCouponCode();
        ApiResponse r = postCoupon(couponJson(code, type, value, minOrderAmount, maxDiscountAmount, totalQuantity));
        assertThat(r.status()).as("create coupon: %s", r).isEqualTo(201);
        return code;
    }

    protected ApiResponse getCoupon(String code) {
        return get("/api/coupons/" + code);
    }

    // =====================================================================
    // 주문
    // =====================================================================

    protected record Line(long productId, int quantity) {
    }

    protected static Line line(long productId, int quantity) {
        return new Line(productId, quantity);
    }

    /** 주문 생성 본문 JSON. couponCode가 null이면 "couponCode":null. */
    protected static String orderJson(String couponCode, Line... lines) {
        List<String> items = new ArrayList<>();
        for (Line l : lines) {
            items.add("{\"productId\":" + l.productId() + ",\"quantity\":" + l.quantity() + "}");
        }
        return "{\"items\":[" + String.join(",", items) + "],\"couponCode\":"
                + (couponCode == null ? "null" : "\"" + couponCode + "\"") + "}";
    }

    protected ApiResponse postOrder(String userId, String idempotencyKey, String rawJson) {
        Map<String, String> h = new LinkedHashMap<>();
        if (userId != null) {
            h.put("X-User-Id", userId);
        }
        if (idempotencyKey != null) {
            h.put("Idempotency-Key", idempotencyKey);
        }
        return post("/api/orders", h, rawJson);
    }

    /** 새 사용자·새 키로 주문한다 (응답 그대로). */
    protected ApiResponse placeOrder(String couponCode, Line... lines) {
        return postOrder(uniqueUser(), uniqueKey(), orderJson(couponCode, lines));
    }

    /** 쿠폰 없는 주문 1건을 만들고(201 확인) 응답을 돌려준다. */
    protected ApiResponse placeOrderOk(long productId, int quantity) {
        ApiResponse r = placeOrder(null, line(productId, quantity));
        assertThat(r.status()).as("place order: %s", r).isEqualTo(201);
        return r;
    }

    protected ApiResponse getOrder(long id) {
        return get("/api/orders/" + id);
    }

    protected ApiResponse postPay(long orderId, String idempotencyKey, String rawJson) {
        Map<String, String> h = new LinkedHashMap<>();
        if (idempotencyKey != null) {
            h.put("Idempotency-Key", idempotencyKey);
        }
        return post("/api/orders/" + orderId + "/pay", h, rawJson);
    }

    protected ApiResponse pay(long orderId, String idempotencyKey, String cardToken) {
        return postPay(orderId, idempotencyKey, "{\"cardToken\":\"" + cardToken + "\"}");
    }

    /** 새 키로 결제한다 (응답 그대로). */
    protected ApiResponse pay(long orderId) {
        return pay(orderId, uniqueKey(), "tok_test");
    }

    protected ApiResponse cancel(long orderId) {
        return post("/api/orders/" + orderId + "/cancel", null, null);
    }

    protected ApiResponse ship(long orderId) {
        return post("/api/orders/" + orderId + "/ship", null, null);
    }

    protected ApiResponse deliver(long orderId) {
        return post("/api/orders/" + orderId + "/deliver", null, null);
    }

    /** query 예: "userId=u1&status=PAID&size=5". */
    protected ApiResponse listOrders(String query) {
        return get("/api/orders" + (query == null || query.isEmpty() ? "" : "?" + query));
    }

    /** 주문 → 결제 승인까지 진행한 PAID 주문 id. */
    protected long newPaidOrder(long productId, int quantity) {
        long orderId = placeOrderOk(productId, quantity).id();
        ApiResponse paid = pay(orderId);
        assertThat(paid.status()).as("pay: %s", paid).isEqualTo(200);
        return orderId;
    }

    // =====================================================================
    // 상태 조회 / 상태 구성 / 공통 단언 (요구사항 테스트용 확장)
    // =====================================================================

    protected int reserved(long productId) {
        return getProduct(productId).json().get("reserved").asInt();
    }

    protected int stock(long productId) {
        return getProduct(productId).json().get("stock").asInt();
    }

    protected int available(long productId) {
        return getProduct(productId).json().get("available").asInt();
    }

    protected long usedCount(String couponCode) {
        return getCoupon(couponCode).longValue("usedCount");
    }

    /** 응답의 시각 필드를 시점(Instant)으로 읽는다. null이면 null. */
    protected static Instant instant(ApiResponse r, String field) {
        String v = r.text(field);
        return v == null ? null : java.time.OffsetDateTime.parse(v).toInstant();
    }

    /**
     * PENDING_PAYMENT 주문을 원하는 상태로 진행시킨다. 가짜 PG는 승인 모드로 되돌려 놓는다.
     * 결제·환불 호출 기록이 쌓이므로, 호출 수를 검증하는 테스트는 이 호출 뒤에 {@code PG.reset()}을 부른다.
     * EXPIRED는 만들 수 없다(짧은 TTL 컨텍스트에서 만료를 기다려야 함).
     */
    protected void driveOrderTo(long orderId, String status) {
        switch (status) {
            case "PENDING_PAYMENT" -> { }
            case "CANCELLED" -> assertThat(cancel(orderId).status()).isEqualTo(200);
            case "PAYMENT_FAILED" -> {
                PG.decline();
                assertThat(pay(orderId).status()).isEqualTo(402);
                PG.approve();
            }
            case "PAID", "SHIPPED", "DELIVERED", "REFUNDED" -> {
                PG.approve();
                assertThat(pay(orderId).status()).isEqualTo(200);
                switch (status) {
                    case "SHIPPED" -> assertThat(ship(orderId).status()).isEqualTo(200);
                    case "DELIVERED" -> {
                        assertThat(ship(orderId).status()).isEqualTo(200);
                        assertThat(deliver(orderId).status()).isEqualTo(200);
                    }
                    case "REFUNDED" -> assertThat(cancel(orderId).status()).isEqualTo(200);
                    default -> { }
                }
            }
            default -> throw new IllegalArgumentException("unsupported status: " + status);
        }
        assertThat(getOrder(orderId).text("status")).isEqualTo(status);
    }

    /** 새 상품(가격 1000, 재고 10)에 수량 1 주문을 만들어 해당 상태까지 진행한 주문 id. */
    protected long newOrderInStatus(String status) {
        long orderId = placeOrderOk(newProduct(1000, 10), 1).id();
        driveOrderTo(orderId, status);
        return orderId;
    }

    /** RFC 9457 Problem Details 공통 단언 (R11.1, R11.2): 상태, Content-Type, 필수 필드, code. */
    protected static void assertProblem(ApiResponse r, int status, String code) {
        assertThat(r.status()).as("response: %s", r).isEqualTo(status);
        assertThat(r.contentType()).as("content-type").startsWith("application/problem+json");
        assertThat(r.json()).as("body is JSON").isNotNull();
        for (String field : List.of("type", "title", "detail", "code")) {
            assertThat(r.text(field)).as("problem field %s in %s", field, r).isNotBlank();
        }
        assertThat(r.json().get("status").asInt()).as("problem.status").isEqualTo(status);
        assertThat(r.code()).as("problem.code").isEqualTo(code);
    }

    // =====================================================================
    // 동시성 / 대기
    // =====================================================================

    /** n개 작업을 동시에 출발시켜(CountDownLatch) 응답을 인덱스 순서로 모은다. */
    protected List<ApiResponse> runConcurrently(int n, IntFunction<ApiResponse> task) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.apply(idx);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<ApiResponse> results = new ArrayList<>();
            for (Future<ApiResponse> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    protected static long countStatus(List<ApiResponse> responses, int status) {
        return responses.stream().filter(r -> r.status() == status).count();
    }

    /** 주문이 기대 상태가 될 때까지 기다린다. */
    protected void awaitOrderStatus(long orderId, String status, Duration atMost) {
        Awaitility.await().atMost(atMost).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(getOrder(orderId).text("status")).isEqualTo(status));
    }
}
