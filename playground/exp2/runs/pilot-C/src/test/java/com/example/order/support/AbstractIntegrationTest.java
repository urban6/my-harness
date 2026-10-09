package com.example.order.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 통합 테스트 베이스 (Phase 3 test-writer 는 이 클래스를 extends 해서 사용한다).
 *
 * <ul>
 *   <li>PostgreSQL Testcontainers 와 WireMock(PG 더블, dynamic port)은 JVM 당 싱글턴이며 모든 서브클래스가 공유한다.</li>
 *   <li>애플리케이션은 RANDOM_PORT 로 실제 기동되므로 동시성/타임아웃 테스트에서 실 HTTP 로 호출한다.</li>
 *   <li>짧은 TTL 이 필요한 테스트는 서브클래스에서
 *       {@code @SpringBootTest(webEnvironment = RANDOM_PORT, properties = "order.payment-ttl=PT3S")} 로 컨텍스트를 분리한다
 *       (이 베이스는 TTL 을 동적 프로퍼티로 고정하지 않으므로 서브클래스 값이 적용된다).</li>
 *   <li>데이터 격리는 TRUNCATE 가 아니라 고유 식별자({@link #uniqueCode()}, {@link #uniqueUser()}, {@link #uniqueKey()})로 한다.
 *       (만료 스윕 스케줄러 및 다른 테스트와 경합 방지)</li>
 *   <li>PG 더블은 매 테스트 전에 reset 된다. 기본 스텁은 없으므로 각 테스트가 stubPg* 헬퍼로 설정한다.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());
    protected static final ObjectMapper JSON = new ObjectMapper();

    static {
        POSTGRES.start();
        WIREMOCK.start();
        Runtime.getRuntime().addShutdownHook(new Thread(WIREMOCK::stop));
    }

    @DynamicPropertySource
    static void baseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payment.gateway.url", WIREMOCK::baseUrl);
    }

    private static final AtomicLong SEQ = new AtomicLong();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetPgDouble() {
        WIREMOCK.resetAll();
    }

    // ---------------------------------------------------------------- unique ids

    protected static String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    protected static String uniqueUser() {
        return "user-" + UUID.randomUUID().toString().substring(0, 18);
    }

    /** 쿠폰 코드 규칙(영문 대문자·숫자 4~20자)을 만족하는 고유 코드. */
    protected static String uniqueCode() {
        return "C" + Long.toString(System.nanoTime(), 36).toUpperCase() + SEQ.incrementAndGet();
    }

    // ---------------------------------------------------------------- raw HTTP

    protected ApiResponse send(String method, String path, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30));
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                String text = body instanceof String s ? s : JSON.writeValueAsString(body);
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(text));
            }
            if (headers != null) {
                headers.forEach(b::header);
            }
            HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String raw = res.body();
            JsonNode node = (raw == null || raw.isBlank()) ? null : JSON.readTree(raw);
            return new ApiResponse(res.statusCode(), res.headers(), raw, node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected ApiResponse get(String path) {
        return send("GET", path, null, null);
    }

    protected ApiResponse post(String path, Object body, Map<String, String> headers) {
        return send("POST", path, body, headers);
    }

    protected static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---------------------------------------------------------------- domain helpers

    protected ObjectNode obj() {
        return JSON.createObjectNode();
    }

    protected ApiResponse createProduct(String name, long price, int stock) {
        return post("/api/products", obj().put("name", name).put("price", price).put("stock", stock), null);
    }

    /** 상품을 만들고 id 를 돌려준다 (201 이 아니면 예외). */
    protected long newProduct(long price, int stock) {
        ApiResponse r = createProduct("product-" + SEQ.incrementAndGet(), price, stock);
        if (r.status() != 201) {
            throw new AssertionError("product creation failed: " + r.status() + " " + r.rawBody());
        }
        return r.id();
    }

    protected ApiResponse getProduct(long id) {
        return get("/api/products/" + id);
    }

    /** body 전체를 직접 지정하는 쿠폰 생성. */
    protected ApiResponse createCoupon(Map<String, Object> body) {
        return post("/api/coupons", body, null);
    }

    /** FIXED/RATE 쿠폰 생성 (현재 기준 -1일 ~ +30일 유효, 최소주문 0, 상한 없음). */
    protected ApiResponse createCoupon(String code, String type, long value, long totalQuantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("type", type);
        body.put("value", value);
        body.put("minOrderAmount", 0);
        body.put("totalQuantity", totalQuantity);
        body.put("validFrom", java.time.OffsetDateTime.now().minusDays(1).toString());
        body.put("validUntil", java.time.OffsetDateTime.now().plusDays(30).toString());
        return createCoupon(body);
    }

    protected ApiResponse getCoupon(String code) {
        return get("/api/coupons/" + code);
    }

    /** items: {productId, quantity} 쌍의 가변 인자 (long, int, long, int ...). couponCode 는 null 가능. */
    protected ApiResponse createOrder(String userId, String idemKey, String couponCode, long... productIdQty) {
        return post("/api/orders", orderBody(couponCode, productIdQty), headers("X-User-Id", userId,
                "Idempotency-Key", idemKey));
    }

    protected ObjectNode orderBody(String couponCode, long... productIdQty) {
        ObjectNode body = obj();
        ArrayNode items = body.putArray("items");
        for (int i = 0; i < productIdQty.length; i += 2) {
            items.addObject().put("productId", productIdQty[i]).put("quantity", (int) productIdQty[i + 1]);
        }
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return body;
    }

    /** 새 키로 주문을 만들고 응답을 돌려준다 (201 이 아니면 예외). */
    protected ApiResponse newOrder(String userId, String couponCode, long... productIdQty) {
        ApiResponse r = createOrder(userId, uniqueKey(), couponCode, productIdQty);
        if (r.status() != 201) {
            throw new AssertionError("order creation failed: " + r.status() + " " + r.rawBody());
        }
        return r;
    }

    protected ApiResponse getOrder(long id) {
        return get("/api/orders/" + id);
    }

    protected ApiResponse pay(long orderId, String idemKey, String cardToken) {
        return post("/api/orders/" + orderId + "/pay", obj().put("cardToken", cardToken),
                headers("Idempotency-Key", idemKey));
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

    protected ApiResponse listOrders(String query) {
        return get("/api/orders" + (query == null || query.isEmpty() ? "" : "?" + query));
    }

    /** 주문 생성 + 결제 승인 스텁 + 결제까지 수행해 PAID 주문을 돌려준다. */
    protected ApiResponse newPaidOrder(String userId, String couponCode, long... productIdQty) {
        ApiResponse order = newOrder(userId, couponCode, productIdQty);
        stubPgPayment("APPROVED", "pay-" + SEQ.incrementAndGet());
        ApiResponse paid = pay(order.id(), uniqueKey(), "tok_ok");
        if (paid.status() != 200) {
            throw new AssertionError("payment failed: " + paid.status() + " " + paid.rawBody());
        }
        return paid;
    }

    protected List<JsonNode> items(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    // ---------------------------------------------------------------- Phase 3 helpers

    /** 응답 시각 문자열을 Instant 로 (오프셋 표기가 달라도 같은 순간이면 같다). */
    protected static java.time.Instant instant(JsonNode node) {
        return java.time.OffsetDateTime.parse(node.asText()).toInstant();
    }

    /** 쿠폰 본문 전체 지정. min/max/from/until 은 null 이면 생략하지 않고 호출자가 정한 값을 그대로 넣는다. */
    protected static Map<String, Object> couponBody(String code, String type, long value, Long min, Long max,
                                                     long total, java.time.OffsetDateTime from,
                                                     java.time.OffsetDateTime until) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("type", type);
        body.put("value", value);
        if (min != null) body.put("minOrderAmount", min);
        if (max != null) body.put("maxDiscountAmount", max);
        body.put("totalQuantity", total);
        body.put("validFrom", from.toString());
        body.put("validUntil", until.toString());
        return body;
    }

    /** 현재 기준 -1일 ~ +30일 유효 쿠폰 생성 후 201 확인. */
    protected String newCoupon(String type, long value, Long min, Long max, long total) {
        String code = uniqueCode();
        ApiResponse r = createCoupon(couponBody(code, type, value, min, max, total,
                java.time.OffsetDateTime.now().minusDays(1), java.time.OffsetDateTime.now().plusDays(30)));
        if (r.status() != 201) {
            throw new AssertionError("coupon creation failed: " + r.status() + " " + r.rawBody());
        }
        return code;
    }

    protected int stockOf(long productId) {
        return getProduct(productId).json("stock").asInt();
    }

    protected int reservedOf(long productId) {
        return getProduct(productId).json("reserved").asInt();
    }

    protected long usedCountOf(String code) {
        return getCoupon(code).json("usedCount").asLong();
    }

    protected String statusOf(long orderId) {
        return getOrder(orderId).json("status").asText();
    }

    protected ApiResponse payAs(long orderId, String idemKey, String cardToken, String userId) {
        return post("/api/orders/" + orderId + "/pay", obj().put("cardToken", cardToken),
                headers("Idempotency-Key", idemKey, "X-User-Id", userId));
    }

    /** 모든 작업을 동시에 출발시켜(CountDownLatch) 결과를 입력 순서대로 돌려준다. */
    protected static <T> List<T> runConcurrently(List<java.util.concurrent.Callable<T>> tasks) {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(tasks.size());
        try {
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(tasks.size());
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<T>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (java.util.concurrent.Future<T> f : futures) {
                out.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    protected static long countStatus(List<ApiResponse> rs, int status) {
        return rs.stream().filter(r -> r.status() == status).count();
    }

    protected static long countCode(List<ApiResponse> rs, int status, String code) {
        return rs.stream().filter(r -> r.status() == status && code.equals(r.code())).count();
    }

    // ---------------------------------------------------------------- PG double (WireMock)

    /** POST /v1/payments -> 200 {paymentId, status}. status: APPROVED | DECLINED */
    protected void stubPgPayment(String status, String paymentId) {
        WIREMOCK.stubFor(WireMock.post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\":\"" + paymentId + "\",\"status\":\"" + status + "\"}")));
    }

    /** POST /v1/payments -> 지연 후 200. 2초 초과 타임아웃 검증용 (delayMillis > 2000). */
    protected void stubPgPaymentDelayed(String status, String paymentId, int delayMillis) {
        WIREMOCK.stubFor(WireMock.post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(200)
                .withFixedDelay(delayMillis)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\":\"" + paymentId + "\",\"status\":\"" + status + "\"}")));
    }

    /** POST /v1/payments -> 상태코드만 (예: 500, 503). */
    protected void stubPgPaymentStatus(int httpStatus) {
        WIREMOCK.stubFor(WireMock.post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(httpStatus)));
    }

    /** POST /v1/payments/{paymentId}/refund -> 200 {paymentId, status: REFUNDED}. */
    protected void stubPgRefund(String paymentId) {
        WIREMOCK.stubFor(WireMock.post(urlEqualTo("/v1/payments/" + paymentId + "/refund")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}")));
    }

    /** 환불 엔드포인트 장애 (상태코드). */
    protected void stubPgRefundStatus(int httpStatus) {
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/v1/payments/.+/refund")).willReturn(aResponse()
                .withStatus(httpStatus)));
    }

    /** 임의 매핑 추가가 필요할 때 사용. */
    protected void stubPg(MappingBuilder mapping) {
        WIREMOCK.stubFor(mapping);
    }

    /** WireMock 이 받은 결제 요청 수. */
    protected int pgPaymentRequestCount() {
        return WIREMOCK.findAll(WireMock
                .postRequestedFor(urlEqualTo("/v1/payments"))).size();
    }

    protected int pgRefundRequestCount() {
        return WIREMOCK.findAll(WireMock
                .postRequestedFor(urlPathMatching("/v1/payments/.+/refund"))).size();
    }
}
