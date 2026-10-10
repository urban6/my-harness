package com.example.order;

import com.example.order.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Fake external PG: card token decides the outcome. */
    static HttpServer pg;
    static final AtomicInteger pgCharges = new AtomicInteger();
    static final AtomicInteger pgRefunds = new AtomicInteger();

    @BeforeAll
    static void startPg() throws IOException {
        pg = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        pg.createContext("/v1/payments", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String resp;
            int code = 200;
            if (path.endsWith("/refund")) {
                pgRefunds.incrementAndGet();
                resp = "{\"paymentId\":\"" + path.split("/")[3] + "\",\"status\":\"REFUNDED\"}";
            } else if (body.contains("tok_error")) {
                code = 500;
                resp = "{}";
            } else {
                pgCharges.incrementAndGet();
                String status = body.contains("tok_declined") ? "DECLINED" : "APPROVED";
                resp = "{\"paymentId\":\"pay_" + UUID.randomUUID() + "\",\"status\":\"" + status + "\"}";
            }
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        pg.start();
    }

    @AfterAll
    static void stopPg() {
        pg.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("payment.gateway.url", () -> "http://127.0.0.1:" + pg.getAddress().getPort());
        r.add("order.payment-ttl", () -> "PT30M");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orderService;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetCounters() {
        pgCharges.set(0);
        pgRefunds.set(0);
    }

    // ---- helpers ----

    record Res(int status, String contentType, String location, JsonNode body) {
    }

    Res call(String method, String path, String body, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) b.header("Content-Type", "application/json");
            for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String text = r.body();
            return new Res(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""),
                    r.headers().firstValue("Location").orElse(null), text.isBlank() ? null : json.readTree(text));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    long product(long price, int stock) {
        Res r = call("POST", "/api/products", "{\"name\":\"p\",\"price\":" + price + ",\"stock\":" + stock + "}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.location()).isEqualTo("/api/products/" + r.body().get("id").asLong());
        return r.body().get("id").asLong();
    }

    JsonNode getProduct(long id) {
        return call("GET", "/api/products/" + id, null).body();
    }

    String coupon(String type, long value, long min, Long max, int qty) {
        String code = "C" + UUID.randomUUID().toString().substring(0, 8);
        Res r = call("POST", "/api/coupons", """
                {"code":"%s","type":"%s","value":%d,"minOrderAmount":%d,"maxDiscountAmount":%s,"totalQuantity":%d,
                 "validFrom":"%s","validUntil":"%s"}""".formatted(code, type, value, min, max, qty,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600)));
        assertThat(r.status()).isEqualTo(201);
        return code;
    }

    Res order(String user, String key, long productId, int qty, String couponCode) {
        String c = couponCode == null ? "" : ",\"couponCode\":\"" + couponCode + "\"";
        return call("POST", "/api/orders", "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]" + c + "}",
                "X-User-Id", user, "Idempotency-Key", key);
    }

    Res order(String user, long productId, int qty) {
        return order(user, UUID.randomUUID().toString(), productId, qty, null);
    }

    Res pay(long orderId, String token) {
        return call("POST", "/api/orders/" + orderId + "/pay", "{\"cardToken\":\"" + token + "\"}",
                "Idempotency-Key", UUID.randomUUID().toString());
    }

    Res post(long orderId, String action) {
        return call("POST", "/api/orders/" + orderId + "/" + action, null);
    }

    // ---- tests ----

    @Test
    void productAndErrorFormat() {
        long id = product(1000, 5);
        JsonNode p = getProduct(id);
        assertThat(p.get("available").asInt()).isEqualTo(5);
        assertThat(p.get("reserved").asInt()).isZero();

        Res missing = call("GET", "/api/products/999999", null);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.contentType()).startsWith("application/problem+json");
        assertThat(missing.body().get("status").asInt()).isEqualTo(404);

        Res invalid = call("POST", "/api/products", "{\"name\":\"\",\"price\":-1,\"stock\":1}");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.contentType()).startsWith("application/problem+json");

        Res noHeader = call("POST", "/api/orders", "{\"items\":[{\"productId\":1,\"quantity\":1}]}");
        assertThat(noHeader.status()).isEqualTo(400);
        assertThat(noHeader.contentType()).startsWith("application/problem+json");
    }

    @Test
    void createOrderReservesStockAndAppliesCoupon() {
        long pid = product(10_000, 10);
        String code = coupon("RATE", 20, 5_000, 3_000L, 5);

        Res r = order("u1", UUID.randomUUID().toString(), pid, 2, code);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.location()).isEqualTo("/api/orders/" + r.body().get("id").asLong());
        assertThat(r.body().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(r.body().get("subtotal").asLong()).isEqualTo(20_000);
        assertThat(r.body().get("discount").asLong()).isEqualTo(3_000); // 20% = 4000, capped at 3000
        assertThat(r.body().get("totalPrice").asLong()).isEqualTo(17_000);
        assertThat(r.body().get("items").get(0).get("unitPrice").asLong()).isEqualTo(10_000);

        JsonNode p = getProduct(pid);
        assertThat(p.get("reserved").asInt()).isEqualTo(2);
        assertThat(p.get("available").asInt()).isEqualTo(8);
        assertThat(call("GET", "/api/coupons/" + code, null).body().get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    void fixedCouponNeverExceedsSubtotalAndRespectsMinimum() {
        long pid = product(1_000, 10);
        String big = coupon("FIXED", 5_000, 0, null, 5);
        assertThat(order("u", UUID.randomUUID().toString(), pid, 1, big).body().get("totalPrice").asLong()).isZero();

        String min = coupon("FIXED", 500, 10_000, null, 5);
        Res r = order("u", UUID.randomUUID().toString(), pid, 1, min);
        assertThat(r.status()).isEqualTo(422);
        // the failed order must not leave reservations behind
        assertThat(getProduct(pid).get("reserved").asInt()).isEqualTo(1);
        assertThat(call("GET", "/api/coupons/" + min, null).body().get("usedCount").asInt()).isZero();
    }

    @Test
    void couponQuantityIsEnforced() {
        long pid = product(1_000, 10);
        String code = coupon("FIXED", 100, 0, null, 1);
        assertThat(order("u", UUID.randomUUID().toString(), pid, 1, code).status()).isEqualTo(201);
        Res second = order("u", UUID.randomUUID().toString(), pid, 1, code);
        assertThat(second.status()).isEqualTo(409);
        assertThat(getProduct(pid).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    void insufficientStockIsRejected() {
        long pid = product(1_000, 2);
        assertThat(order("u", pid, 3).status()).isEqualTo(409);
        assertThat(getProduct(pid).get("reserved").asInt()).isZero();
        assertThat(order("u", 987654321L, 1).status()).isEqualTo(404);
    }

    @Test
    void createIsIdempotent() {
        long pid = product(1_000, 5);
        String key = UUID.randomUUID().toString();
        Res a = order("u", key, pid, 2, null);
        Res b = order("u", key, pid, 2, null);
        assertThat(a.status()).isEqualTo(201);
        assertThat(b.status()).isEqualTo(201);
        assertThat(b.body().get("id").asLong()).isEqualTo(a.body().get("id").asLong());
        assertThat(getProduct(pid).get("reserved").asInt()).isEqualTo(2);

        Res different = order("u", key, pid, 3, null);
        assertThat(different.status()).isEqualTo(422);
    }

    @Test
    void concurrentOrdersNeverOversell() throws Exception {
        long pid = product(100, 5);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            results.add(pool.submit(() -> order("u", pid, 1).status()));
        }
        int created = 0;
        for (Future<Integer> f : results) if (f.get() == 201) created++;
        pool.shutdown();
        assertThat(created).isEqualTo(5);
        JsonNode p = getProduct(pid);
        assertThat(p.get("reserved").asInt()).isEqualTo(5);
        assertThat(p.get("available").asInt()).isZero();
    }

    @Test
    void concurrentSameKeyCreatesOneOrder() throws Exception {
        long pid = product(100, 10);
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Res>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) results.add(pool.submit(() -> order("u", key, pid, 1, null)));
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Future<Res> f : results) {
            Res r = f.get();
            assertThat(r.status()).isEqualTo(201);
            ids.add(r.body().get("id").asLong());
        }
        pool.shutdown();
        assertThat(ids).hasSize(1);
        assertThat(getProduct(pid).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    void payApprovedCommitsStockAndIsIdempotent() {
        long pid = product(1_000, 5);
        long oid = order("u", pid, 2).body().get("id").asLong();
        String key = UUID.randomUUID().toString();
        String body = "{\"cardToken\":\"tok_ok\"}";
        Res r = call("POST", "/api/orders/" + oid + "/pay", body, "Idempotency-Key", key);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status").asText()).isEqualTo("PAID");
        assertThat(r.body().get("paidAt").isNull()).isFalse();

        JsonNode p = getProduct(pid);
        assertThat(p.get("stock").asInt()).isEqualTo(3);
        assertThat(p.get("reserved").asInt()).isZero();

        Res again = call("POST", "/api/orders/" + oid + "/pay", body, "Idempotency-Key", key);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("status").asText()).isEqualTo("PAID");
        assertThat(pgCharges.get()).isEqualTo(1);

        assertThat(pay(oid, "tok_ok").status()).isEqualTo(409); // different key => real conflict
    }

    @Test
    void payDeclinedReleasesReservationAndCoupon() {
        long pid = product(1_000, 5);
        String code = coupon("FIXED", 100, 0, null, 3);
        long oid = order("u", UUID.randomUUID().toString(), pid, 2, code).body().get("id").asLong();
        Res r = pay(oid, "tok_declined");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status").asText()).isEqualTo("PAYMENT_FAILED");
        JsonNode p = getProduct(pid);
        assertThat(p.get("stock").asInt()).isEqualTo(5);
        assertThat(p.get("reserved").asInt()).isZero();
        assertThat(call("GET", "/api/coupons/" + code, null).body().get("usedCount").asInt()).isZero();
    }

    @Test
    void gatewayErrorLeavesOrderPayable() {
        long pid = product(1_000, 5);
        long oid = order("u", pid, 1).body().get("id").asLong();
        Res r = pay(oid, "tok_error");
        assertThat(r.status()).isEqualTo(502);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(call("GET", "/api/orders/" + oid, null).body().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(pay(oid, "tok_ok").body().get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void cancelPendingReleasesAndCancelPaidRefunds() {
        long pid = product(1_000, 5);
        String code = coupon("FIXED", 100, 0, null, 3);
        long o1 = order("u", UUID.randomUUID().toString(), pid, 2, code).body().get("id").asLong();
        assertThat(post(o1, "cancel").body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(getProduct(pid).get("reserved").asInt()).isZero();
        assertThat(call("GET", "/api/coupons/" + code, null).body().get("usedCount").asInt()).isZero();
        assertThat(post(o1, "cancel").status()).isEqualTo(409);

        long o2 = order("u", pid, 2).body().get("id").asLong();
        pay(o2, "tok_ok");
        assertThat(getProduct(pid).get("stock").asInt()).isEqualTo(3);
        Res r = post(o2, "cancel");
        assertThat(r.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(pgRefunds.get()).isEqualTo(1);
        assertThat(getProduct(pid).get("stock").asInt()).isEqualTo(5);
    }

    @Test
    void shipAndDeliverFollowStateMachine() {
        long pid = product(1_000, 5);
        long oid = order("u", pid, 1).body().get("id").asLong();
        assertThat(post(oid, "ship").status()).isEqualTo(409);
        pay(oid, "tok_ok");
        assertThat(post(oid, "deliver").status()).isEqualTo(409);
        assertThat(post(oid, "ship").body().get("status").asText()).isEqualTo("SHIPPED");
        assertThat(post(oid, "cancel").status()).isEqualTo(409);
        assertThat(post(oid, "deliver").body().get("status").asText()).isEqualTo("DELIVERED");
        assertThat(post(999999, "ship").status()).isEqualTo(404);
    }

    @Test
    void unpaidOrdersExpireAndReleaseHolds() {
        long pid = product(1_000, 5);
        String code = coupon("FIXED", 100, 0, null, 3);
        long oid = order("u", UUID.randomUUID().toString(), pid, 3, code).body().get("id").asLong();
        jdbc.update("UPDATE orders SET expires_at = now() - interval '1 second' WHERE id = ?", oid);

        orderService.expireDue();

        assertThat(call("GET", "/api/orders/" + oid, null).body().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(getProduct(pid).get("reserved").asInt()).isZero();
        assertThat(call("GET", "/api/coupons/" + code, null).body().get("usedCount").asInt()).isZero();
        assertThat(pay(oid, "tok_ok").status()).isEqualTo(409);
    }

    @Test
    void expiryIsAlsoAppliedLazily() {
        long pid = product(1_000, 5);
        long oid = order("u", pid, 1).body().get("id").asLong();
        jdbc.update("UPDATE orders SET expires_at = now() - interval '1 second' WHERE id = ?", oid);
        Res r = pay(oid, "tok_ok");
        assertThat(r.status()).isEqualTo(409);
        assertThat(call("GET", "/api/orders/" + oid, null).body().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(getProduct(pid).get("reserved").asInt()).isZero();
        assertThat(pgCharges.get()).isZero();
    }

    @Test
    void listOrdersPaginatesWithCursor() {
        long pid = product(100, 100);
        String user = "lister-" + UUID.randomUUID();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(order(user, pid, 1).body().get("id").asLong());
        pay(ids.get(0), "tok_ok");

        Res first = call("GET", "/api/orders?userId=" + user + "&size=2", null);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("content")).hasSize(2);
        assertThat(first.body().get("content").get(0).get("id").asLong()).isEqualTo(ids.get(4));
        String cursor = first.body().get("nextCursor").asText();

        Res second = call("GET", "/api/orders?userId=" + user + "&size=2&cursor=" + cursor, null);
        assertThat(second.body().get("content").get(0).get("id").asLong()).isEqualTo(ids.get(2));
        Res third = call("GET", "/api/orders?userId=" + user + "&size=2&cursor=" + second.body().get("nextCursor").asText(), null);
        assertThat(third.body().get("content")).hasSize(1);
        assertThat(third.body().get("nextCursor").isNull()).isTrue();

        Res paid = call("GET", "/api/orders?userId=" + user + "&status=PAID", null);
        assertThat(paid.body().get("content")).hasSize(1);
        assertThat(call("GET", "/api/orders?status=BOGUS", null).status()).isEqualTo(400);
    }
}
