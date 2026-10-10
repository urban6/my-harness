package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트 공통 헬퍼(스프링 설정 어노테이션 없음). 컨텍스트 설정은 {@link IntegrationTestBase} 또는
 * 각 테스트 클래스가 직접 지정한다. 모든 테스트는 고유한 user/key/code/상품을 만들어 서로 격리된다.
 */
public abstract class ApiTestSupport {

    /** JVM 당 하나의 가짜 PG. 테스트마다 {@link FakePaymentGateway#reset()} 으로 초기화한다. */
    public static final FakePaymentGateway PG = new FakePaymentGateway();

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetFakeGateway() {
        PG.reset();
    }

    // ---------------------------------------------------------------- 저수준 HTTP

    protected ResponseEntity<JsonNode> post(String path, Object body, String... headerPairs) {
        return exchange(HttpMethod.POST, path, body, headerPairs);
    }

    protected ResponseEntity<JsonNode> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body, String... headerPairs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.set(headerPairs[i], headerPairs[i + 1]);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    protected static int code(ResponseEntity<JsonNode> res) {
        return res.getStatusCode().value();
    }

    // ---------------------------------------------------------------- 데이터 생성 헬퍼

    protected static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    protected static String uniqueUser() {
        return "user-" + UUID.randomUUID();
    }

    protected static String uniqueKey() {
        return UUID.randomUUID().toString();
    }

    /** 영문 대문자·숫자 12자 쿠폰 코드. */
    protected static String uniqueCode() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder("C");
        for (int i = 0; i < 11; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    protected JsonNode createProduct(String name, long price, int stock) {
        ResponseEntity<JsonNode> res = post("/api/products", map("name", name, "price", price, "stock", stock));
        if (code(res) != 201) {
            throw new AssertionError("product creation failed: " + res);
        }
        return res.getBody();
    }

    /** 고유 이름의 상품을 만들고 id 를 돌려준다. */
    protected long newProduct(long price, int stock) {
        return createProduct("p-" + UUID.randomUUID(), price, stock).get("id").asLong();
    }

    /** 기본값(기간 -1일~+30일, 수량 1000, 최소금액·상한 생략)을 채운 쿠폰 요청 본문. 키를 바꾸거나 지워서 쓴다. */
    protected static Map<String, Object> couponBody(String code, String type, long value) {
        Instant now = Instant.now();
        return map("code", code, "type", type, "value", value, "totalQuantity", 1000,
                "validFrom", now.minus(1, ChronoUnit.DAYS).toString(),
                "validUntil", now.plus(30, ChronoUnit.DAYS).toString());
    }

    protected JsonNode createCoupon(Map<String, Object> body) {
        ResponseEntity<JsonNode> res = post("/api/coupons", body);
        if (code(res) != 201) {
            throw new AssertionError("coupon creation failed: " + res);
        }
        return res.getBody();
    }

    /** 새 쿠폰을 만들고 code 를 돌려준다. */
    protected String newCoupon(String type, long value, int totalQuantity) {
        Map<String, Object> body = couponBody(uniqueCode(), type, value);
        body.put("totalQuantity", totalQuantity);
        return createCoupon(body).get("code").asText();
    }

    protected static Map<String, Object> item(long productId, int quantity) {
        return map("productId", productId, "quantity", quantity);
    }

    /** items(p1, 2, p2, 1) -> [{productId:p1,quantity:2},{productId:p2,quantity:1}] */
    protected static List<Map<String, Object>> items(long... productIdQuantityPairs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i + 1 < productIdQuantityPairs.length; i += 2) {
            list.add(item(productIdQuantityPairs[i], (int) productIdQuantityPairs[i + 1]));
        }
        return list;
    }

    protected ResponseEntity<JsonNode> placeOrder(String user, String key, String couponCode,
                                                  List<Map<String, Object>> items) {
        Map<String, Object> body = map("items", items);
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return post("/api/orders", body, "X-User-Id", user, "Idempotency-Key", key);
    }

    protected ResponseEntity<JsonNode> placeOrder(String user, String couponCode, long productId, int quantity) {
        return placeOrder(user, uniqueKey(), couponCode, items(productId, quantity));
    }

    /** 201 을 단언하고 주문 본문을 돌려준다(새 사용자, 새 키). */
    protected JsonNode newOrder(long productId, int quantity) {
        return newOrder(uniqueUser(), null, items(productId, quantity));
    }

    protected JsonNode newOrder(String user, String couponCode, List<Map<String, Object>> items) {
        ResponseEntity<JsonNode> res = placeOrder(user, uniqueKey(), couponCode, items);
        assertThat(code(res)).as("order creation body=%s", res.getBody()).isEqualTo(201);
        return res.getBody();
    }

    protected ResponseEntity<JsonNode> pay(long orderId, String key, String cardToken) {
        return post("/api/orders/" + orderId + "/pay", map("cardToken", cardToken), "Idempotency-Key", key);
    }

    protected ResponseEntity<JsonNode> pay(long orderId) {
        return pay(orderId, uniqueKey(), "tok_ok");
    }

    /** 승인 결제를 수행하고 200 을 단언한 뒤 본문을 돌려준다. */
    protected JsonNode payOk(long orderId) {
        ResponseEntity<JsonNode> res = pay(orderId);
        assertThat(code(res)).as("pay body=%s", res.getBody()).isEqualTo(200);
        return res.getBody();
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

    protected JsonNode product(long id) {
        ResponseEntity<JsonNode> res = get("/api/products/" + id);
        assertThat(code(res)).isEqualTo(200);
        return res.getBody();
    }

    protected JsonNode coupon(String couponCode) {
        ResponseEntity<JsonNode> res = get("/api/coupons/" + couponCode);
        assertThat(code(res)).isEqualTo(200);
        return res.getBody();
    }

    protected JsonNode order(long id) {
        ResponseEntity<JsonNode> res = get("/api/orders/" + id);
        assertThat(code(res)).isEqualTo(200);
        return res.getBody();
    }

    protected String statusOf(long orderId) {
        return order(orderId).get("status").asText();
    }

    /** 재고(stock) / 예약(reserved) 을 한 번에 단언한다. */
    protected void assertStock(long productId, int stock, int reserved) {
        JsonNode p = product(productId);
        assertThat(p.get("stock").asInt()).as("stock of %s", p).isEqualTo(stock);
        assertThat(p.get("reserved").asInt()).as("reserved of %s", p).isEqualTo(reserved);
        assertThat(p.get("available").asInt()).isEqualTo(stock - reserved);
    }

    protected void assertUsedCount(String couponCode, int expected) {
        assertThat(coupon(couponCode).get("usedCount").asInt()).isEqualTo(expected);
    }

    // ---------------------------------------------------------------- 동시성 헬퍼

    /**
     * n 개의 요청을 서로 다른 스레드에서 동시에 시작한다. 모든 스레드가 준비된 뒤 래치를 열어 출발선을 맞추므로
     * sleep 에 의존하지 않는다. 결과는 인덱스 순서로 돌려준다.
     */
    protected List<ResponseEntity<JsonNode>> concurrently(int n, IntFunction<ResponseEntity<JsonNode>> task) {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index);
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("worker threads did not become ready");
            }
            start.countDown();
            List<ResponseEntity<JsonNode>> results = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new AssertionError("concurrent execution failed", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 가짜 PG 가 요청을 n 건 이상 받을 때까지 기다린다(이벤트 기반 폴링, 최대 10초). */
    protected void awaitGatewayRequests(int n) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (PG.requests().size() < n) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("fake PG did not receive " + n + " request(s) in time");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for fake PG", e);
            }
        }
    }

    protected static long count(List<ResponseEntity<JsonNode>> results, int status) {
        return results.stream().filter(r -> r.getStatusCode().value() == status).count();
    }

    // ---------------------------------------------------------------- 단언 헬퍼

    protected static void assertStatus(ResponseEntity<JsonNode> res, int expected) {
        assertThat(res.getStatusCode().value()).as("status of response body=%s", res.getBody()).isEqualTo(expected);
    }

    /** RFC 9457 Problem Details 형태(Content-Type, type/title/status/detail/code)와 상태·code 를 단언한다. */
    protected static void assertProblem(ResponseEntity<JsonNode> res, int status, String code) {
        JsonNode b = res.getBody();
        assertThat(res.getStatusCode().value()).as("status, body=%s", b).isEqualTo(status);
        MediaType ct = res.getHeaders().getContentType();
        assertThat(ct).as("Content-Type").isNotNull();
        assertThat(ct.getType() + "/" + ct.getSubtype()).isEqualTo("application/problem+json");
        assertThat(b).isNotNull();
        for (String field : new String[] {"type", "title", "detail", "code"}) {
            assertThat(b.hasNonNull(field)).as("field %s in %s", field, b).isTrue();
            assertThat(b.get(field).asText()).as("field %s", field).isNotBlank();
        }
        assertThat(b.get("status").isInt()).isTrue();
        assertThat(b.get("status").asInt()).isEqualTo(status);
        assertThat(b.get("code").asText()).as("code in %s", b).isEqualTo(code);
    }

    protected static OffsetDateTime time(JsonNode node, String field) {
        assertThat(node.hasNonNull(field)).as("%s present in %s", field, node).isTrue();
        return OffsetDateTime.parse(node.get(field).asText());
    }

    // ---------------------------------------------------------------- DB 직접 조작(테스트 전용)

    /** 주문을 DB 에서 직접 PAID 로 만든다(PG 를 거치지 않고 payment_id 를 지정). 재고·예약도 결제 반영 상태로 맞춘다. */
    protected void forcePaid(long orderId, String paymentId) {
        jdbc.update("update orders set status='PAID', paid_at=now(), payment_id=? where id=?", paymentId, orderId);
        jdbc.update("""
                update products p set stock = p.stock - oi.quantity, reserved = p.reserved - oi.quantity
                from order_items oi where oi.product_id = p.id and oi.order_id = ?""", orderId);
    }
}
