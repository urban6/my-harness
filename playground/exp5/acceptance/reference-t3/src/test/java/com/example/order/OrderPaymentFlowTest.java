package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderPaymentFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * PG mock: behaviour chosen by cardToken (ok / decline / fail / slow); refund mode switchable. Refunds follow the
     * changed contract: Idempotency-Key replays the first result, cumulative refunds above the paid amount are 400.
     */
    static final HttpServer pg;
    static final AtomicInteger paymentCalls = new AtomicInteger();
    static final AtomicInteger refundCalls = new AtomicInteger();
    static final AtomicReference<String> refundMode = new AtomicReference<>("ok");
    record PgPayment(String paymentId, long amount) {
    }
    record PgRefund(String paymentId, String key, long amount) {
    }
    static final Map<Long, PgPayment> paymentsByOrder = new ConcurrentHashMap<>();
    static final Map<String, Long> paidByPayment = new ConcurrentHashMap<>();
    static final Map<String, PgRefund> refundsByKey = new ConcurrentHashMap<>();

    static {
        try {
            pg = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        pg.setExecutor(Executors.newCachedThreadPool());
        ObjectMapper om = new ObjectMapper();
        pg.createContext("/v1/payments", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String out;
            if (path.endsWith("/refund")) {
                String id = path.split("/")[3];
                if ("fail".equals(refundMode.get())) {
                    status = 500;
                    out = "{}";
                } else {
                    synchronized (refundsByKey) {
                        refundCalls.incrementAndGet();
                        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
                        long amount = om.readTree(body).path("amount").asLong();
                        PgRefund prev = refundsByKey.get(key);
                        if (prev == null && refundedOf(id) + amount > paidByPayment.getOrDefault(id, 0L)) {
                            status = 400;
                            out = "{}";
                        } else {
                            PgRefund r = prev != null ? prev : new PgRefund(id, key, amount);
                            refundsByKey.putIfAbsent(key, r);
                            out = "{\"paymentId\":\"" + id + "\",\"status\":\"REFUNDED\",\"amount\":"
                                    + r.amount() + "}";
                        }
                    }
                }
            } else {
                paymentCalls.incrementAndGet();
                JsonNode req = om.readTree(body);
                String token = req.path("cardToken").asText();
                String paymentId = "pay-" + UUID.randomUUID();
                paymentsByOrder.put(req.path("orderId").asLong(), new PgPayment(paymentId, req.path("amount").asLong()));
                paidByPayment.put(paymentId, req.path("amount").asLong());
                switch (token) {
                    case "decline" -> out = "{\"paymentId\":\"" + paymentId + "\",\"status\":\"DECLINED\"}";
                    case "fail" -> {
                        status = 500;
                        out = "{}";
                    }
                    case "slow" -> {
                        sleep(3000);
                        out = "{\"paymentId\":\"" + paymentId + "\",\"status\":\"APPROVED\"}";
                    }
                    default -> {
                        sleep(300);
                        out = "{\"paymentId\":\"" + paymentId + "\",\"status\":\"APPROVED\"}";
                    }
                }
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            try {
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // client gave up (timeout test)
            }
            ex.close();
        });
        pg.start();
    }

    static long refundedOf(String paymentId) {
        return refundsByKey.values().stream().filter(r -> r.paymentId().equals(paymentId))
                .mapToLong(PgRefund::amount).sum();
    }

    /** Sum of PG refunds for the payment of this order (0 when the order never reached the PG). */
    static long pgRefunded(long orderId) {
        PgPayment p = paymentsByOrder.get(orderId);
        return p == null ? 0 : refundedOf(p.paymentId());
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    static void stopPg() {
        pg.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("PAYMENT_GATEWAY_URL", () -> "http://127.0.0.1:" + pg.getAddress().getPort());
        r.add("ORDER_PAYMENT_TTL", () -> "PT6S");
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper om = new ObjectMapper();

    record Res(int status, JsonNode body, String contentType, String location) {
    }

    Res call(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = res.body().isEmpty() ? null : om.readTree(res.body());
        return new Res(res.statusCode(), json, res.headers().firstValue("Content-Type").orElse(""),
                res.headers().firstValue("Location").orElse(null));
    }

    long product(long price, long stock) throws Exception {
        Res r = call("POST", "/api/products", "{\"name\":\"p\",\"price\":" + price + ",\"stock\":" + stock + "}");
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    Res order(String user, String key, String body) throws Exception {
        return call("POST", "/api/orders", body, "X-User-Id", user, "Idempotency-Key", key);
    }

    Res pay(long orderId, String key, String token) throws Exception {
        return call("POST", "/api/orders/" + orderId + "/pay", "{\"cardToken\":\"" + token + "\"}",
                "Idempotency-Key", key);
    }

    String uniq() {
        return UUID.randomUUID().toString();
    }

    @Test
    void couponOrderIdempotencyPayShipDeliver() throws Exception {
        long p = product(10_000_000, 2000);
        String now = Instant.now().minusSeconds(60).toString();
        String later = Instant.now().plusSeconds(3600).toString();
        String code = "RATE" + (System.nanoTime() % 100000);
        Res c = call("POST", "/api/coupons", "{\"code\":\"" + code + "\",\"type\":\"RATE\",\"value\":10,"
                + "\"maxDiscountAmount\":5000000000,\"totalQuantity\":3,\"validFrom\":\"" + now
                + "\",\"validUntil\":\"" + later + "\"}");
        assertThat(c.status()).isEqualTo(201);
        assertThat(c.location()).endsWith("/api/coupons/" + code);
        assertThat(c.body().get("usedCount").asLong()).isZero();
        assertThat(c.body().get("minOrderAmount").asLong()).isZero();

        String body = "{\"items\":[{\"productId\":" + p + ",\"quantity\":1000}],\"couponCode\":\"" + code + "\"}";
        String key = uniq();
        Res o = order("u1", key, body);
        assertThat(o.status()).isEqualTo(201);
        assertThat(o.body().get("subtotal").asLong()).isEqualTo(10_000_000_000L);
        assertThat(o.body().get("discount").asLong()).isEqualTo(1_000_000_000L);
        assertThat(o.body().get("totalPrice").asLong()).isEqualTo(9_000_000_000L);
        assertThat(o.body().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.body().get("paidAt").isNull()).isTrue();
        long id = o.body().get("id").asLong();

        Res replay = order("u1", key, body);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(o.body());
        assertThat(order("u2", key, body).body().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(order("u1", uniq(), body).body().get("code").asText()).isEqualTo("COUPON_NOT_APPLICABLE");

        JsonNode prod = call("GET", "/api/products/" + p, null).body();
        assertThat(prod.get("reserved").asLong()).isEqualTo(1000);
        assertThat(prod.get("available").asLong()).isEqualTo(1000);

        String payKey = uniq();
        Res paid = pay(id, payKey, "ok");
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.body().get("status").asText()).isEqualTo("PAID");
        assertThat(paid.body().get("paidAt").isNull()).isFalse();
        assertThat(pay(id, payKey, "ok").body()).isEqualTo(paid.body());
        assertThat(pay(id, uniq(), "ok").body().get("code").asText()).isEqualTo("INVALID_STATE");

        prod = call("GET", "/api/products/" + p, null).body();
        assertThat(prod.get("stock").asLong()).isEqualTo(1000);
        assertThat(prod.get("reserved").asLong()).isZero();

        assertThat(call("POST", "/api/orders/" + id + "/deliver", null).status()).isEqualTo(409);
        assertThat(call("POST", "/api/orders/" + id + "/ship", null).body().get("status").asText())
                .isEqualTo("SHIPPED");
        assertThat(call("POST", "/api/orders/" + id + "/deliver", null).body().get("status").asText())
                .isEqualTo("DELIVERED");
        Res cancel = call("POST", "/api/orders/" + id + "/cancel", null);
        assertThat(cancel.status()).isEqualTo(409);
        assertThat(cancel.contentType()).startsWith("application/problem+json");
    }

    @Test
    void declineGatewayFailureTimeoutAndRefund() throws Exception {
        long p = product(1000, 10);
        String body = "{\"items\":[{\"productId\":" + p + ",\"quantity\":2}]}";

        long declined = order("u", uniq(), body).body().get("id").asLong();
        Res d = pay(declined, uniq(), "decline");
        assertThat(d.status()).isEqualTo(402);
        assertThat(d.body().get("code").asText()).isEqualTo("PAYMENT_DECLINED");
        assertThat(call("GET", "/api/orders/" + declined, null).body().get("status").asText())
                .isEqualTo("PAYMENT_FAILED");

        long o = order("u", uniq(), body).body().get("id").asLong();
        assertThat(pay(o, uniq(), "fail").status()).isEqualTo(503);
        long start = System.nanoTime();
        Res slow = pay(o, uniq(), "slow");
        assertThat(slow.status()).isEqualTo(503);
        assertThat(slow.body().get("code").asText()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2900);
        assertThat(call("GET", "/api/products/" + p, null).body().get("reserved").asLong()).isEqualTo(2);

        assertThat(pay(o, uniq(), "ok").status()).isEqualTo(200);
        refundMode.set("fail");
        assertThat(call("POST", "/api/orders/" + o + "/cancel", null).status()).isEqualTo(503);
        refundMode.set("ok");
        Res refunded = call("POST", "/api/orders/" + o + "/cancel", null);
        assertThat(refunded.body().get("status").asText()).isEqualTo("REFUNDED");
        JsonNode prod = call("GET", "/api/products/" + p, null).body();
        assertThat(prod.get("stock").asLong()).isEqualTo(10);
        assertThat(prod.get("reserved").asLong()).isZero();
    }

    @Test
    void concurrentOrdersAndPayments() throws Exception {
        long p = product(100, 10);
        long q = product(100, 1000);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> order("c", uniq(), "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}]}").status());
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            statuses.add(f.get());
        }
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(10);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(10);
        assertThat(call("GET", "/api/products/" + p, null).body().get("reserved").asLong()).isEqualTo(10);

        long r1 = product(100, 1000);
        long r2 = product(100, 1000);
        tasks.clear();
        for (int i = 0; i < 20; i++) {
            String body = i % 2 == 0
                    ? "{\"items\":[{\"productId\":" + r1 + ",\"quantity\":1},{\"productId\":" + r2 + ",\"quantity\":2}]}"
                    : "{\"items\":[{\"productId\":" + r2 + ",\"quantity\":2},{\"productId\":" + r1 + ",\"quantity\":1}]}";
            tasks.add(() -> order("d", uniq(), body).status());
        }
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            assertThat(f.get()).isEqualTo(201);
        }
        assertThat(call("GET", "/api/products/" + r1, null).body().get("reserved").asLong()).isEqualTo(20);
        assertThat(call("GET", "/api/products/" + r2, null).body().get("reserved").asLong()).isEqualTo(40);

        long id = order("c2", uniq(), "{\"items\":[{\"productId\":" + q + ",\"quantity\":1}]}").body()
                .get("id").asLong();
        int before = paymentCalls.get();
        tasks.clear();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> pay(id, uniq(), "ok").status());
        }
        statuses.clear();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(4);
        assertThat(paymentCalls.get() - before).isEqualTo(1);
    }

    @Test
    void expiryAndValidationAndListing() throws Exception {
        long p = product(500, 5);
        String user = "list-" + uniq().substring(0, 8);
        grant(user, 1000);
        long first = order(user, uniq(), "{\"items\":[{\"productId\":" + p + ",\"quantity\":3}],"
                + "\"usePoints\":800}").body().get("id").asLong();
        assertThat(balance(user)).isEqualTo(200);
        assertThat(call("GET", "/api/products/" + p, null).body().get("reserved").asLong()).isEqualTo(3);
        for (int i = 0; i < 2; i++) {
            order(user, uniq(), "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}]}");
        }

        Res page1 = call("GET", "/api/orders?userId=" + user + "&size=2", null);
        assertThat(page1.body().get("content")).hasSize(2);
        String cursor = page1.body().get("nextCursor").asText();
        order(user, uniq(), "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}]}");
        Res page2 = call("GET", "/api/orders?userId=" + user + "&size=2&cursor=" + cursor, null);
        assertThat(page2.body().get("content")).hasSize(1);
        assertThat(page2.body().get("content").get(0).get("id").asLong()).isEqualTo(first);
        assertThat(page2.body().get("nextCursor").isNull()).isTrue();

        assertThat(call("GET", "/api/orders?status=NOPE", null).status()).isEqualTo(400);
        assertThat(call("GET", "/api/orders?size=0", null).status()).isEqualTo(400);
        Res badCursor = call("GET", "/api/orders?cursor=not-a-cursor", null);
        assertThat(badCursor.status()).isEqualTo(400);
        assertThat(badCursor.contentType()).startsWith("application/problem+json");
        Res parse = call("POST", "/api/products", "{bad json");
        assertThat(parse.status()).isEqualTo(400);
        assertThat(parse.body().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(parse.body().has("type")).isTrue();
        assertThat(parse.body().has("title")).isTrue();
        assertThat(parse.body().has("detail")).isTrue();
        assertThat(call("POST", "/api/orders", "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}]}",
                "X-User-Id", "u").status()).isEqualTo(400);
        assertThat(call("GET", "/api/orders/999999999", null).body().get("code").asText())
                .isEqualTo("ORDER_NOT_FOUND");

        Thread.sleep(8500);
        assertThat(balance(user)).isEqualTo(1000); // P2.6 points come back on expiry
        assertThat(call("GET", "/api/orders/" + first, null).body().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(call("GET", "/api/products/" + p, null).body().get("reserved").asLong()).isZero();
    }

    // ------------------------------------------------------------------ points / partial refunds (change request)

    long grant(String user, long amount) throws Exception {
        Res r = call("POST", "/api/points/" + user + "/grants", "{\"amount\":" + amount + "}");
        assertThat(r.status()).isEqualTo(200);
        return r.body().get("balance").asLong();
    }

    long balance(String user) throws Exception {
        Res r = call("GET", "/api/points/" + user, null);
        assertThat(r.status()).isEqualTo(200);
        return r.body().get("balance").asLong();
    }

    Res refund(long orderId, String key, String items) throws Exception {
        return call("POST", "/api/orders/" + orderId + "/refunds", "{\"items\":" + items + "}",
                "Idempotency-Key", key);
    }

    static String item(long productId, long quantity) {
        return "{\"productId\":" + productId + ",\"quantity\":" + quantity + "}";
    }

    String fixedCoupon(long value, long totalQuantity) throws Exception {
        String code = "FIX" + (System.nanoTime() % 1_000_000_000L);
        Res c = call("POST", "/api/coupons", "{\"code\":\"" + code + "\",\"type\":\"FIXED\",\"value\":" + value
                + ",\"totalQuantity\":" + totalQuantity + ",\"validFrom\":\"" + Instant.now().minusSeconds(60)
                + "\",\"validUntil\":\"" + Instant.now().plusSeconds(3600) + "\"}");
        assertThat(c.status()).isEqualTo(201);
        return code;
    }

    JsonNode getOrder(long id) throws Exception {
        return call("GET", "/api/orders/" + id, null).body();
    }

    long stock(long productId) throws Exception {
        return call("GET", "/api/products/" + productId, null).body().get("stock").asLong();
    }

    String code(Res r) {
        return r.body().get("code").asText();
    }

    @Test
    void pointAccounts() throws Exception {
        String user = "pt-" + uniq().substring(0, 8);
        assertThat(balance(user)).isZero();
        Res g = call("POST", "/api/points/" + user + "/grants", "{\"amount\":10000000}");
        assertThat(g.status()).isEqualTo(200);
        assertThat(g.body().get("userId").asText()).isEqualTo(user);
        assertThat(g.body().get("balance").asLong()).isEqualTo(10_000_000);
        assertThat(grant(user, 1)).isEqualTo(10_000_001);
        assertThat(balance(user)).isEqualTo(10_000_001);

        for (String bad : List.of("{\"amount\":0}", "{\"amount\":10000001}", "{\"amount\":1.5}",
                "{\"amount\":\"5\"}", "{}", "{\"amount\":-1}")) {
            Res r = call("POST", "/api/points/" + user + "/grants", bad);
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(r.contentType()).startsWith("application/problem+json");
            assertThat(code(r)).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(call("POST", "/api/points/%20%20/grants", "{\"amount\":1}").status()).isEqualTo(400);
        assertThat(call("POST", "/api/points/" + "x".repeat(51) + "/grants", "{\"amount\":1}").status())
                .isEqualTo(400);
        assertThat(call("POST", "/api/points/" + "x".repeat(50) + "/grants", "{\"amount\":1}").status())
                .isEqualTo(200);
        assertThat(balance(user)).isEqualTo(10_000_001);
        assertThat(call("POST", "/api/points//grants", "{\"amount\":1}").status()).isEqualTo(400);
        assertThat(call("GET", "/api/points/", null).status()).isEqualTo(400);
        assertThat(call("GET", "/api/points/%20", null).status()).isEqualTo(400);
    }

    @Test
    void ordersWithPointsPaymentAndRestoration() throws Exception {
        String user = "pp-" + uniq().substring(0, 8);
        grant(user, 10_000);
        long p = product(1000, 100);
        String one = "[" + item(p, 2) + "]";

        // P2.1 validation
        for (String bad : List.of("-1", "1.5", "\"1\"", "null", "true")) {
            Res r = order(user, uniq(), "{\"items\":" + one + ",\"usePoints\":" + bad + "}");
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(code(r)).isEqualTo("VALIDATION_ERROR");
        }

        // P2.3 precedence: 404 -> stock -> coupon -> POINTS_EXCEED_TOTAL -> INSUFFICIENT_POINTS
        assertThat(code(order(user, uniq(), "{\"items\":[" + item(999_999_999L, 1) + "],\"usePoints\":99999999}")))
                .isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(code(order(user, uniq(), "{\"items\":[" + item(p, 101) + "],\"usePoints\":99999999}")))
                .isEqualTo("INSUFFICIENT_STOCK");
        String coupon = fixedCoupon(500, 10);
        String held = "pp-held-" + uniq().substring(0, 8);
        grant(held, 100);
        String couponOrder = "{\"items\":" + one + ",\"couponCode\":\"" + coupon + "\",\"usePoints\":%d}";
        assertThat(order(held, uniq(), couponOrder.formatted(0)).status()).isEqualTo(201);
        assertThat(code(order(held, uniq(), couponOrder.formatted(99_999_999)))).isEqualTo("COUPON_NOT_APPLICABLE");
        Res exceed = order(user, uniq(), couponOrder.formatted(1501)); // total 1500
        assertThat(exceed.status()).isEqualTo(409);
        assertThat(code(exceed)).isEqualTo("POINTS_EXCEED_TOTAL");
        assertThat(code(order(held, uniq(), "{\"items\":" + one + ",\"usePoints\":2001}"))).isEqualTo("POINTS_EXCEED_TOTAL");
        Res insufficient = order(held, uniq(), "{\"items\":" + one + ",\"usePoints\":101}");
        assertThat(code(insufficient)).isEqualTo("INSUFFICIENT_POINTS");
        assertThat(insufficient.contentType()).startsWith("application/problem+json");
        assertThat(balance(user)).isEqualTo(10_000);
        assertThat(call("GET", "/api/products/" + p, null).body().get("reserved").asLong()).isEqualTo(2);

        // P2.4 / P2.5
        String key = uniq();
        String body = "{\"items\":" + one + ",\"usePoints\":1500}";
        Res o = order(user, key, body);
        assertThat(o.status()).isEqualTo(201);
        assertThat(o.body().get("pointAmount").asLong()).isEqualTo(1500);
        assertThat(o.body().get("cardAmount").asLong()).isEqualTo(500);
        assertThat(o.body().get("refundedAmount").asLong()).isZero();
        assertThat(o.body().get("items").get(0).get("refundedQuantity").asLong()).isZero();
        assertThat(balance(user)).isEqualTo(8500);
        // P2.7 idempotency
        assertThat(order(user, key, body).body()).isEqualTo(o.body());
        assertThat(code(order(user, key, "{\"items\":" + one + ",\"usePoints\":1501}")))
                .isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(code(order(user, key, "{\"items\":" + one + "}"))).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        Res noPoints = order(user, uniq(), "{\"items\":" + one + "}");
        assertThat(noPoints.body().get("pointAmount").asLong()).isZero();
        assertThat(noPoints.body().get("cardAmount").asLong()).isEqualTo(2000);
        assertThat(balance(user)).isEqualTo(8500);

        // P3.3 PG failure: nothing changes; decline: PAYMENT_FAILED and points back (P2.6)
        long id = o.body().get("id").asLong();
        assertThat(pay(id, uniq(), "fail").status()).isEqualTo(503);
        assertThat(balance(user)).isEqualTo(8500);
        assertThat(pay(id, uniq(), "decline").status()).isEqualTo(402);
        assertThat(getOrder(id).get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(paymentsByOrder.get(id).amount()).isEqualTo(500); // P3.1 amount = cardAmount
        assertThat(balance(user)).isEqualTo(10_000);

        // P2.6 cancel restores points
        long c = order(user, uniq(), "{\"items\":" + one + ",\"usePoints\":700}").body().get("id").asLong();
        assertThat(balance(user)).isEqualTo(9300);
        assertThat(call("POST", "/api/orders/" + c + "/cancel", null).body().get("status").asText())
                .isEqualTo("CANCELLED");
        assertThat(balance(user)).isEqualTo(10_000);

        // P3.2 cardAmount 0: no PG call
        long full = order(user, uniq(), "{\"items\":" + one + ",\"usePoints\":2000}").body().get("id").asLong();
        int before = paymentCalls.get();
        Res paid = pay(full, uniq(), "decline");
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.body().get("status").asText()).isEqualTo("PAID");
        assertThat(paid.body().get("cardAmount").asLong()).isZero();
        assertThat(paymentCalls.get()).isEqualTo(before);
        assertThat(balance(user)).isEqualTo(8000);
    }

    @Test
    void partialRefundsAndCancel() throws Exception {
        String user = "pr-" + uniq().substring(0, 8);
        grant(user, 5000);
        long a = product(1000, 10);
        long b = product(333, 10);
        String coupon = fixedCoupon(100, 5);
        // subtotal 3666, discount 100, total 3566, points 1000, card 2566
        String body = "{\"items\":[" + item(a, 3) + "," + item(b, 2) + "],\"couponCode\":\"" + coupon
                + "\",\"usePoints\":1000}";
        long id = order(user, uniq(), body).body().get("id").asLong();
        assertThat(code(refund(id, uniq(), "[" + item(a, 1) + "]"))).isEqualTo("INVALID_STATE");
        assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);
        String paymentId = paymentsByOrder.get(id).paymentId();
        assertThat(paymentsByOrder.get(id).amount()).isEqualTo(2566);

        // P4.2 400 / 404
        assertThat(call("POST", "/api/orders/" + id + "/refunds", "{\"items\":[" + item(a, 1) + "]}").status())
                .isEqualTo(400);
        assertThat(refund(id, "x".repeat(65), "[" + item(a, 1) + "]").status()).isEqualTo(400);
        assertThat(refund(id, uniq(), "[]").status()).isEqualTo(400);
        assertThat(refund(id, uniq(), "[" + item(a, 0) + "]").status()).isEqualTo(400);
        assertThat(refund(id, uniq(), "[" + item(a, 1) + "," + item(a, 1) + "]").status()).isEqualTo(400);
        Res missing = refund(999_999_999L, uniq(), "[" + item(a, 1) + "]");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("ORDER_NOT_FOUND");
        // P4.4
        assertThat(code(refund(id, uniq(), "[" + item(a, 4) + "]"))).isEqualTo("REFUND_QUANTITY_EXCEEDED");
        assertThat(code(refund(id, uniq(), "[" + item(a, 1) + "," + item(999_999_999L, 1) + "]")))
                .isEqualTo("REFUND_QUANTITY_EXCEEDED");

        // P4.8 PG failure: nothing changes
        refundMode.set("fail");
        Res down = refund(id, uniq(), "[" + item(a, 1) + "]");
        refundMode.set("ok");
        assertThat(down.status()).isEqualTo(503);
        assertThat(code(down)).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(getOrder(id).get("status").asText()).isEqualTo("PAID");
        assertThat(stock(a)).isEqualTo(7);

        // first refund: cumulative floor(3566*1000/3666) = 972, all on the card (P4.5, P4.6, P4.7)
        String key1 = uniq();
        Res r1 = refund(id, key1, "[" + item(a, 1) + "]");
        assertThat(r1.status()).isEqualTo(200);
        assertThat(r1.body().get("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(r1.body().get("refundedAmount").asLong()).isEqualTo(972);
        assertThat(r1.body().get("items").get(0).get("refundedQuantity").asLong()).isEqualTo(1);
        assertThat(r1.body().get("items").get(1).get("refundedQuantity").asLong()).isZero();
        assertThat(refundsByKey.get(key1)).isEqualTo(new PgRefund(paymentId, key1, 972));
        assertThat(stock(a)).isEqualTo(8);
        assertThat(balance(user)).isEqualTo(4000);
        // P4.10 replay / mismatch, independent key space
        int calls = refundCalls.get();
        assertThat(refund(id, key1, "[" + item(a, 1) + "]").body()).isEqualTo(r1.body());
        assertThat(refundCalls.get()).isEqualTo(calls);
        assertThat(code(refund(id, key1, "[" + item(a, 2) + "]"))).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");

        // second refund: cumulative floor(3566*3333/3666) = 3242 -> 2270 = card 1594 (rest) + points 676
        String key2 = uniq();
        Res r2 = refund(id, key2, "[" + item(a, 2) + "," + item(b, 1) + "]");
        assertThat(r2.body().get("refundedAmount").asLong()).isEqualTo(3242);
        assertThat(refundsByKey.get(key2).amount()).isEqualTo(1594);
        assertThat(balance(user)).isEqualTo(4676);
        assertThat(stock(a)).isEqualTo(10);
        assertThat(stock(b)).isEqualTo(9);
        assertThat(call("GET", "/api/coupons/" + coupon, null).body().get("usedCount").asLong()).isEqualTo(1);
        assertThat(call("GET", "/api/orders?userId=" + user + "&status=PARTIALLY_REFUNDED", null).body()
                .get("content")).hasSize(1);

        // last unit: no card left -> no PG call; REFUNDED, total exact, coupon restored (P4.9)
        calls = refundCalls.get();
        Res r3 = refund(id, uniq(), "[" + item(b, 1) + "]");
        assertThat(r3.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(r3.body().get("refundedAmount").asLong()).isEqualTo(3566);
        assertThat(refundCalls.get()).isEqualTo(calls);
        assertThat(balance(user)).isEqualTo(5000);
        assertThat(pgRefunded(id)).isEqualTo(2566);
        assertThat(call("GET", "/api/coupons/" + coupon, null).body().get("usedCount").asLong()).isZero();
        assertThat(code(refund(id, uniq(), "[" + item(b, 1) + "]"))).isEqualTo("INVALID_STATE");

        // P5.1 cancel of a partially refunded order uses cancel-{orderId}
        long id2 = order(user, uniq(), body).body().get("id").asLong();
        pay(id2, uniq(), "ok");
        refund(id2, uniq(), "[" + item(b, 2) + "]"); // floor(3566*666/3666) = 647
        refundMode.set("fail");
        assertThat(call("POST", "/api/orders/" + id2 + "/cancel", null).status()).isEqualTo(503);
        refundMode.set("ok");
        assertThat(getOrder(id2).get("refundedAmount").asLong()).isEqualTo(647);
        Res cancelled = call("POST", "/api/orders/" + id2 + "/cancel", null);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(cancelled.body().get("refundedAmount").asLong()).isEqualTo(3566);
        assertThat(cancelled.body().get("items").get(0).get("refundedQuantity").asLong()).isEqualTo(3);
        assertThat(refundsByKey.get("cancel-" + id2).amount()).isEqualTo(2566 - 647);
        assertThat(pgRefunded(id2)).isEqualTo(2566);
        assertThat(balance(user)).isEqualTo(5000);
        assertThat(stock(a)).isEqualTo(10);
        assertThat(stock(b)).isEqualTo(10);

        // P5.2 ship from PARTIALLY_REFUNDED; shipped orders cannot be refunded or cancelled
        long id3 = order(user, uniq(), "{\"items\":[" + item(a, 2) + "]}").body().get("id").asLong();
        assertThat(pay(id3, key1, "ok").status()).isEqualTo(200); // refund keys do not clash with pay keys
        refund(id3, uniq(), "[" + item(a, 1) + "]");
        assertThat(call("POST", "/api/orders/" + id3 + "/ship", null).body().get("status").asText())
                .isEqualTo("SHIPPED");
        assertThat(code(refund(id3, uniq(), "[" + item(a, 1) + "]"))).isEqualTo("INVALID_STATE");
        assertThat(code(refund(id3, uniq(), "[" + item(a, 5) + "]"))).isEqualTo("INVALID_STATE");
        assertThat(code(call("POST", "/api/orders/" + id3 + "/cancel", null))).isEqualTo("INVALID_STATE");
    }

    @Test
    void concurrentPointsAndRefunds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        // P6.1
        String user = "cp-" + uniq().substring(0, 8);
        grant(user, 1000);
        long p = product(1000, 100);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> order(user, uniq(), "{\"items\":[" + item(p, 1) + "],\"usePoints\":600}").status());
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            statuses.add(f.get());
        }
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(4);
        assertThat(balance(user)).isEqualTo(400);

        // P6.2
        long q = product(999, 100);
        long id = order(user, uniq(), "{\"items\":[" + item(q, 3) + "," + item(p, 1) + "],\"usePoints\":400}")
                .body().get("id").asLong();
        pay(id, uniq(), "ok");
        tasks.clear();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> refund(id, uniq(), "[" + item(q, 1) + "]").status());
        }
        statuses.clear();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            statuses.add(f.get());
        }
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(3);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(2);
        JsonNode o = getOrder(id);
        assertThat(o.get("items").get(0).get("refundedQuantity").asLong()).isEqualTo(3);
        long cardRefunded = Math.min(o.get("refundedAmount").asLong(), o.get("cardAmount").asLong());
        assertThat(pgRefunded(id)).isEqualTo(cardRefunded);

        // P4.10 concurrent same key: processed once
        long id2 = order("cp2", uniq(), "{\"items\":[" + item(q, 5) + "]}").body().get("id").asLong();
        pay(id2, uniq(), "ok");
        String key = uniq();
        tasks.clear();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> {
                Res r = refund(id2, key, "[" + item(q, 1) + "]");
                return r.status() == 409 ? (code(r).equals("IDEMPOTENCY_IN_PROGRESS") ? 409 : -1) : r.status();
            });
        }
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            assertThat(f.get()).isIn(200, 409);
        }
        assertThat(getOrder(id2).get("items").get(0).get("refundedQuantity").asLong()).isEqualTo(1);

        // P6.3 cancel racing partial refunds
        long stockBefore = stock(q);
        long id3 = order("cp3", uniq(), "{\"items\":[" + item(q, 4) + "," + item(p, 2) + "]}").body()
                .get("id").asLong();
        pay(id3, uniq(), "ok");
        tasks.clear();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            tasks.add(() -> n == 3 ? call("POST", "/api/orders/" + id3 + "/cancel", null).status()
                    : refund(id3, uniq(), "[" + item(n % 2 == 0 ? q : p, 1) + "]").status());
        }
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            assertThat(f.get()).isIn(200, 409);
        }
        pool.shutdown();
        JsonNode done = getOrder(id3);
        assertThat(done.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(done.get("refundedAmount").asLong()).isEqualTo(done.get("totalPrice").asLong());
        assertThat(done.get("items").get(0).get("refundedQuantity").asLong()).isEqualTo(4);
        assertThat(pgRefunded(id3)).isEqualTo(done.get("cardAmount").asLong());
        assertThat(stock(q)).isEqualTo(stockBefore);
    }
}
