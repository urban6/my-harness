package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 모든 통합 테스트의 공통 베이스.
 *
 * <ul>
 *   <li>PostgreSQL(postgres:16-alpine) 컨테이너 1개를 JVM 전체에서 공유한다 (static 초기화에서 start, 종료는 Ryuk).</li>
 *   <li>PG 스텁({@link #PG})도 JVM 전체에서 1개를 공유하고 URL을 {@code payment.gateway.url} 로 주입한다.
 *       매 테스트 전에 {@link PaymentGatewayStub#reset()} 으로 초기화된다.</li>
 *   <li>서브클래스에서 {@code @TestPropertySource(properties = "order.payment-ttl=PT3S")} 등으로 설정을 바꿀 수 있다
 *       (컨텍스트는 달라지지만 컨테이너·스텁은 공유).</li>
 *   <li>DB를 비우려면 {@link #cleanDatabase()} 를 호출한다 (자동 호출 안 함 - 필요한 테스트만 쓴다).</li>
 *   <li>HTTP 헬퍼({@link #post}, {@link #get} 등)와 자주 쓰는 픽스처 생성기({@link #createProduct}, {@link #createCoupon},
 *       {@link #createOrder}, {@link #pay})를 제공한다.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    protected static final PaymentGatewayStub PG = new PaymentGatewayStub();

    static {
        POSTGRES.start();
        PG.start();
    }

    @DynamicPropertySource
    static void registerGateway(DynamicPropertyRegistry registry) {
        // 환경 변수 이름(PAYMENT_GATEWAY_URL)으로 주입한다 -> application.yml 의 ${PAYMENT_GATEWAY_URL:...} 플레이스홀더 경로(C4)를 그대로 탄다.
        registry.add("PAYMENT_GATEWAY_URL", PG::baseUrl);
    }

    @LocalServerPort
    protected int port;
    @Autowired
    protected TestRestTemplate rest;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetPaymentGatewayStub() {
        PG.reset();
    }

    protected void cleanDatabase() {
        jdbc.execute("TRUNCATE order_items, orders, coupons, products, idempotency_keys RESTART IDENTITY CASCADE");
    }

    // ------------------------------------------------------------------ HTTP 헬퍼

    protected ResponseEntity<String> get(String path) {
        return rest.getForEntity(path, String.class);
    }

    protected ResponseEntity<String> post(String path, Object body, String... headerNameValuePairs) {
        return exchange(HttpMethod.POST, path, body, headerNameValuePairs);
    }

    protected ResponseEntity<String> exchange(HttpMethod method, String path, Object body,
                                              String... headerNameValuePairs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < headerNameValuePairs.length; i += 2) {
            headers.set(headerNameValuePairs[i], headerNameValuePairs[i + 1]);
        }
        Object payload = body instanceof String || body == null ? body : toJson(body);
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }

    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("JSON 파싱 실패: " + response.getBody(), e);
        }
    }

    protected String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 픽스처 생성기

    protected long createProduct(String name, long price, int stock) {
        ResponseEntity<String> r = post("/api/products", Map.of("name", name, "price", price, "stock", stock));
        if (r.getStatusCode().value() != 201) {
            throw new IllegalStateException("상품 생성 실패: " + r.getStatusCode() + " " + r.getBody());
        }
        return json(r).get("id").asLong();
    }

    protected ResponseEntity<String> getProduct(long id) {
        return get("/api/products/" + id);
    }

    /** FIXED/RATE 쿠폰 등록. maxDiscountAmount는 null이면 생략(제한 없음). 유효기간은 [지금-1일, 지금+30일). */
    protected ResponseEntity<String> createCouponResponse(String code, String type, long value, long minOrderAmount,
                                                          Long maxDiscountAmount, long totalQuantity) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("type", type);
        body.put("value", value);
        body.put("minOrderAmount", minOrderAmount);
        if (maxDiscountAmount != null) {
            body.put("maxDiscountAmount", maxDiscountAmount);
        }
        body.put("totalQuantity", totalQuantity);
        body.put("validFrom", Instant.now().minusSeconds(86_400).toString());
        body.put("validUntil", Instant.now().plusSeconds(30L * 86_400).toString());
        return post("/api/coupons", body);
    }

    protected void createCoupon(String code, String type, long value, long minOrderAmount, Long maxDiscountAmount,
                                long totalQuantity) {
        ResponseEntity<String> r = createCouponResponse(code, type, value, minOrderAmount, maxDiscountAmount, totalQuantity);
        if (r.getStatusCode().value() != 201) {
            throw new IllegalStateException("쿠폰 생성 실패: " + r.getStatusCode() + " " + r.getBody());
        }
    }

    protected ResponseEntity<String> getCoupon(String code) {
        return get("/api/coupons/" + code);
    }

    /** 주문 본문. items는 {productId, quantity} 쌍을 {@link #item}으로 만들어 넘긴다. couponCode는 null 가능. */
    protected Map<String, Object> orderBody(String couponCode, Map<String, Object>... items) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("items", List.of(items));
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return body;
    }

    protected static Map<String, Object> item(long productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    protected ResponseEntity<String> createOrder(String userId, String idempotencyKey, Object body) {
        return post("/api/orders", body, "X-User-Id", userId, "Idempotency-Key", idempotencyKey);
    }

    protected ResponseEntity<String> getOrder(long id) {
        return get("/api/orders/" + id);
    }

    protected ResponseEntity<String> pay(long orderId, String idempotencyKey, String cardToken) {
        return post("/api/orders/" + orderId + "/pay", Map.of("cardToken", cardToken),
                "Idempotency-Key", idempotencyKey);
    }

    protected ResponseEntity<String> cancel(long orderId) {
        return post("/api/orders/" + orderId + "/cancel", null);
    }

    protected ResponseEntity<String> ship(long orderId) {
        return post("/api/orders/" + orderId + "/ship", null);
    }

    protected ResponseEntity<String> deliver(long orderId) {
        return post("/api/orders/" + orderId + "/deliver", null);
    }

    // ------------------------------------------------------------------ 추가 헬퍼 (요구사항별 테스트용)

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * 미리 인코딩된 경로/쿼리를 URI 로 그대로 보낸다 (String 오버로드는 URI 템플릿으로 재인코딩해 %00 -> %2500 이 된다).
     * 예: {@code getRaw("/api/coupons/A%00B")}
     */
    protected ResponseEntity<String> getRaw(String rawPathAndQuery) {
        return rest.getForEntity(URI.create(baseUrl() + rawPathAndQuery), String.class);
    }

    /** 요청마다 고유한 문자열 (사용자 id, Idempotency-Key 용). 50자 이하. */
    protected static String uid(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 영문 대문자·숫자 4~20자의 고유 쿠폰 코드. */
    protected static String uniqueCode(String prefix) {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String code = (prefix + random).replaceAll("[^A-Z0-9]", "");
        return code.length() > 20 ? code.substring(0, 20) : code;
    }

    protected static int statusOf(ResponseEntity<String> response) {
        return response.getStatusCode().value();
    }

    /** 본문 {@code code} 필드 (오류 응답용). */
    protected String codeOf(ResponseEntity<String> response) {
        return json(response).path("code").asText();
    }

    protected ResponseEntity<String> postWithKey(String path, Object body, String idempotencyKey, String... more) {
        String[] headers = new String[more.length + 2];
        headers[0] = "Idempotency-Key";
        headers[1] = idempotencyKey;
        System.arraycopy(more, 0, headers, 2, more.length);
        return post(path, body, headers);
    }

    /** 오류 응답이 RFC 9457 + 기대 상태/code 인지 한꺼번에 단언한다 (R11.1~R11.3). */
    protected void assertProblem(ResponseEntity<String> response, int expectedStatus, String expectedCode) {
        assertThat(statusOf(response)).as("HTTP status, body=%s", response.getBody()).isEqualTo(expectedStatus);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("Content-Type=%s", response.getHeaders().getContentType()).isTrue();
        JsonNode body = json(response);
        assertThat(body.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(body.path("code").asText()).isEqualTo(expectedCode);
    }

    /** 새 상품을 만들고 (id) 반환. */
    protected long product(long price, int stock) {
        return createProduct(uid("p"), price, stock);
    }

    /** 고유 키로 주문을 만들고 201 을 단언한 뒤 응답 JSON 을 돌려준다. */
    protected JsonNode orderOk(String userId, Object body) {
        ResponseEntity<String> r = createOrder(userId, uid("k"), body);
        assertThat(statusOf(r)).as("주문 생성 응답: %s", r.getBody()).isEqualTo(201);
        return json(r);
    }

    protected long orderIdOk(String userId, Object body) {
        return orderOk(userId, body).get("id").asLong();
    }

    /** 고유 키로 결제하고 200 을 단언한 뒤 응답 JSON 을 돌려준다. */
    protected JsonNode payOk(long orderId) {
        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok_ok");
        assertThat(statusOf(r)).as("결제 응답: %s", r.getBody()).isEqualTo(200);
        return json(r);
    }

    protected String orderStatus(long orderId) {
        return json(getOrder(orderId)).get("status").asText();
    }

    protected int reservedOf(long productId) {
        return json(getProduct(productId)).get("reserved").asInt();
    }

    protected int stockOf(long productId) {
        return json(getProduct(productId)).get("stock").asInt();
    }

    protected int availableOf(long productId) {
        return json(getProduct(productId)).get("available").asInt();
    }

    protected long usedCountOf(String couponCode) {
        return json(getCoupon(couponCode)).get("usedCount").asLong();
    }

    /** 유효기간을 직접 지정하는 쿠폰 등록 (기간 전/후 시나리오용). */
    protected ResponseEntity<String> createCouponWithPeriod(String code, String type, long value, long minOrderAmount,
                                                            Long maxDiscountAmount, long totalQuantity,
                                                            Instant validFrom, Instant validUntil) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("type", type);
        body.put("value", value);
        body.put("minOrderAmount", minOrderAmount);
        if (maxDiscountAmount != null) {
            body.put("maxDiscountAmount", maxDiscountAmount);
        }
        body.put("totalQuantity", totalQuantity);
        body.put("validFrom", validFrom.toString());
        body.put("validUntil", validUntil.toString());
        return post("/api/coupons", body);
    }

    /** n 개의 작업을 동시에 출발시켜(CountDownLatch) 결과를 입력 순서대로 모은다. */
    protected <T> List<T> runConcurrently(int n, IntFunction<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                Callable<T> callable = () -> {
                    ready.countDown();
                    start.await();
                    return task.apply(idx);
                };
                futures.add(pool.submit(callable));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("모든 스레드 준비").isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("동시 실행 실패", e);
        } finally {
            pool.shutdownNow();
        }
    }

    protected static long countStatus(List<ResponseEntity<String>> responses, int status) {
        return responses.stream().filter(r -> r.getStatusCode().value() == status).count();
    }

    /** 조건이 참이 될 때까지 interval 간격으로 폴링. timeout 안에 참이 되면 true. */
    protected static boolean awaitCondition(Duration timeout, Duration interval, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    protected static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
