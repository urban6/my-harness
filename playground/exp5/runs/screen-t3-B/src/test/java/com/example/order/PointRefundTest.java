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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** P1-P6: point accounts, mixed point/card payment and partial refunds. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PointRefundTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    record PgCall(String path, String idempotencyKey, long amount, String orderId) {
    }

    /**
     * PG mock following the new contract: payments by cardToken (ok / decline / fail), refunds with a body
     * {amount}, replayed per Idempotency-Key, 400 when the cumulative refund exceeds the payment amount.
     */
    static final HttpServer pg;
    static final List<PgCall> pgCalls = new CopyOnWriteArrayList<>();
    static final Map<String, Long> paymentAmounts = new ConcurrentHashMap<>();
    static final Map<String, Long> refundedByPayment = new ConcurrentHashMap<>();
    static final Map<String, String> refundReplies = new ConcurrentHashMap<>();
    static final AtomicReference<String> refundMode = new AtomicReference<>("ok");

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
            JsonNode body = om.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            int status = 200;
            String out;
            if (path.endsWith("/refund")) {
                String paymentId = path.split("/")[3];
                long amount = body.path("amount").asLong();
                pgCalls.add(new PgCall(path, key, amount, null));
                if ("fail".equals(refundMode.get())) {
                    status = 500;
                    out = "{}";
                } else {
                    synchronized (PointRefundTest.class) {
                        String replay = refundReplies.get(key);
                        if (replay != null) {
                            out = replay;
                        } else {
                            long total = refundedByPayment.merge(paymentId, amount, Long::sum);
                            if (total > paymentAmounts.getOrDefault(paymentId, 0L)) {
                                refundedByPayment.merge(paymentId, -amount, Long::sum);
                                status = 400;
                                out = "{}";
                            } else {
                                out = "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\",\"amount\":"
                                        + amount + "}";
                                refundReplies.put(key, out);
                            }
                        }
                    }
                }
            } else {
                String paymentId = "pay-" + UUID.randomUUID();
                long amount = body.path("amount").asLong();
                pgCalls.add(new PgCall(path, key, amount, body.path("orderId").asText()));
                paymentAmounts.put(paymentId, amount);
                switch (body.path("cardToken").asText()) {
                    case "decline" -> out = "{\"paymentId\":\"" + paymentId + "\",\"status\":\"DECLINED\"}";
                    case "fail" -> {
                        status = 500;
                        out = "{}";
                    }
                    default -> out = "{\"paymentId\":\"" + paymentId + "\",\"status\":\"APPROVED\"}";
                }
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
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
        r.add("PAYMENT_GATEWAY_URL", () -> "http://127.0.0.1:" + pg.getAddress().getPort());
        r.add("ORDER_PAYMENT_TTL", () -> "PT5S");
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper om = new ObjectMapper();

    record Res(int status, JsonNode body, String contentType) {
        String code() {
            return body.get("code").asText();
        }

        long num(String field) {
            return body.get(field).asLong();
        }
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
        return new Res(res.statusCode(), json, res.headers().firstValue("Content-Type").orElse(""));
    }

    String uniq() {
        return UUID.randomUUID().toString();
    }

    long product(long price, long stock) throws Exception {
        Res r = call("POST", "/api/products", "{\"name\":\"p\",\"price\":" + price + ",\"stock\":" + stock + "}");
        assertThat(r.status()).isEqualTo(201);
        return r.num("id");
    }

    JsonNode getProduct(long id) throws Exception {
        return call("GET", "/api/products/" + id, null).body();
    }

    Res grant(String user, long amount) throws Exception {
        return call("POST", "/api/points/" + user + "/grants", "{\"amount\":" + amount + "}");
    }

    long balance(String user) throws Exception {
        return call("GET", "/api/points/" + user, null).num("balance");
    }

    /** items: productId, quantity pairs. */
    static String items(long... pq) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pq.length; i += 2) {
            sb.append(i > 0 ? "," : "").append("{\"productId\":").append(pq[i]).append(",\"quantity\":")
                    .append(pq[i + 1]).append("}");
        }
        return sb.append("]").toString();
    }

    Res order(String user, String key, String items, String extra) throws Exception {
        return call("POST", "/api/orders", "{\"items\":" + items + (extra == null ? "" : "," + extra) + "}",
                "X-User-Id", user, "Idempotency-Key", key);
    }

    Res orderWithPoints(String user, String items, long usePoints) throws Exception {
        return order(user, uniq(), items, "\"usePoints\":" + usePoints);
    }

    Res pay(long orderId, String key, String token) throws Exception {
        return call("POST", "/api/orders/" + orderId + "/pay", "{\"cardToken\":\"" + token + "\"}",
                "Idempotency-Key", key);
    }

    Res refund(long orderId, String key, String items) throws Exception {
        return call("POST", "/api/orders/" + orderId + "/refunds", "{\"items\":" + items + "}",
                "Idempotency-Key", key);
    }

    Res getOrder(long id) throws Exception {
        return call("GET", "/api/orders/" + id, null);
    }

    Res cancel(long id) throws Exception {
        return call("POST", "/api/orders/" + id + "/cancel", null);
    }

    /** A paid order: user with `points` granted, 2 products, subtotal 1666, FIXED coupon 100 -> total 1566. */
    record Paid(String user, long a, long b, long orderId, String payKey, String couponCode) {
    }

    Paid paidOrder(long granted, long usePoints, boolean withCoupon) throws Exception {
        String user = "u-" + uniq();
        long a = product(1000, 100);
        long b = product(333, 100);
        String coupon = null;
        if (withCoupon) {
            coupon = "C" + Long.toString(System.nanoTime(), 36).toUpperCase();
            coupon = coupon.substring(0, Math.min(20, coupon.length()));
            Res c = call("POST", "/api/coupons", "{\"code\":\"" + coupon + "\",\"type\":\"FIXED\",\"value\":100,"
                    + "\"totalQuantity\":5,\"validFrom\":\"" + Instant.now().minusSeconds(60)
                    + "\",\"validUntil\":\"" + Instant.now().plusSeconds(3600) + "\"}");
            assertThat(c.status()).isEqualTo(201);
        }
        if (granted > 0) {
            grant(user, granted);
        }
        Res o = order(user, uniq(), items(a, 1, b, 2),
                "\"usePoints\":" + usePoints + (coupon == null ? "" : ",\"couponCode\":\"" + coupon + "\""));
        assertThat(o.status()).isEqualTo(201);
        String payKey = uniq();
        Res p = pay(o.num("id"), payKey, "ok");
        assertThat(p.status()).isEqualTo(200);
        return new Paid(user, a, b, o.num("id"), payKey, coupon);
    }

    long pgRefundSum(List<String> keys) {
        return pgCalls.stream().filter(c -> c.path().endsWith("/refund") && keys.contains(c.idempotencyKey()))
                .mapToLong(PgCall::amount).sum();
    }

    // ------------------------------------------------------------------------------------------------- P1

    @Test
    void p1PointAccount() throws Exception {
        String user = "p1-" + uniq();
        Res none = call("GET", "/api/points/" + user, null);
        assertThat(none.status()).isEqualTo(200);
        assertThat(none.body().get("userId").asText()).isEqualTo(user);
        assertThat(none.num("balance")).isZero();

        Res g = grant(user, 700);
        assertThat(g.status()).isEqualTo(200);
        assertThat(g.num("balance")).isEqualTo(700);
        assertThat(grant(user, 10_000_000).num("balance")).isEqualTo(10_000_700);
        assertThat(balance(user)).isEqualTo(10_000_700);

        for (String bad : new String[] {"{\"amount\":0}", "{\"amount\":-5}", "{\"amount\":10000001}", "{}",
                "{\"amount\":1.5}", "{\"amount\":\"7\"}", "{bad"}) {
            Res r = call("POST", "/api/points/" + user + "/grants", bad);
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(r.contentType()).startsWith("application/problem+json");
            assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(grant("x".repeat(51), 1).status()).isEqualTo(400);
        assertThat(grant("%20", 1).status()).isEqualTo(400);
        assertThat(grant("x".repeat(50), 1).status()).isEqualTo(200);
        assertThat(balance(user)).isEqualTo(10_000_700); // rejected grants changed nothing
    }

    // ------------------------------------------------------------------------------------------------- P2

    @Test
    void p2UsePointsOnOrder() throws Exception {
        String user = "p2-" + uniq();
        long p = product(1000, 10);
        grant(user, 5000);

        Res o = orderWithPoints(user, items(p, 2), 700);
        assertThat(o.status()).isEqualTo(201);
        assertThat(o.num("totalPrice")).isEqualTo(2000);
        assertThat(o.num("pointAmount")).isEqualTo(700);
        assertThat(o.num("cardAmount")).isEqualTo(1300);
        assertThat(o.num("refundedAmount")).isZero();
        assertThat(o.body().get("items").get(0).get("refundedQuantity").asLong()).isZero();
        assertThat(balance(user)).isEqualTo(4300);

        // usePoints omitted = 0
        Res plain = order(user, uniq(), items(p, 1), null);
        assertThat(plain.num("pointAmount")).isZero();
        assertThat(plain.num("cardAmount")).isEqualTo(1000);
        assertThat(balance(user)).isEqualTo(4300);

        // 400: negative / non-integer usePoints
        for (String bad : new String[] {"-1", "1.5", "\"3\""}) {
            assertThat(order(user, uniq(), items(p, 1), "\"usePoints\":" + bad).status()).as(bad).isEqualTo(400);
        }

        // 409s
        Res exceed = orderWithPoints(user, items(p, 1), 1001);
        assertThat(exceed.status()).isEqualTo(409);
        assertThat(exceed.code()).isEqualTo("POINTS_EXCEED_TOTAL");
        assertThat(exceed.contentType()).startsWith("application/problem+json");
        Res poor = orderWithPoints(user, items(p, 5), 4301); // total 5000 >= 4301 > balance 4300
        assertThat(poor.status()).isEqualTo(409);
        assertThat(poor.code()).isEqualTo("INSUFFICIENT_POINTS");
        // both apply -> POINTS_EXCEED_TOTAL first
        assertThat(orderWithPoints(user, items(p, 1), 5000).code()).isEqualTo("POINTS_EXCEED_TOTAL");
        // exactly the total is fine
        assertThat(orderWithPoints(user, items(p, 1), 1000).status()).isEqualTo(201);
        assertThat(balance(user)).isEqualTo(3300);

        // priority: stock -> coupon -> points
        assertThat(orderWithPoints(user, items(p, 500), 999_999_999).code()).isEqualTo("INSUFFICIENT_STOCK");
        Res noCoupon = order(user, uniq(), items(p, 1), "\"couponCode\":\"NOPE1\",\"usePoints\":1");
        assertThat(noCoupon.status()).isEqualTo(404);
        String coupon = "MIN" + (System.nanoTime() % 100000);
        call("POST", "/api/coupons", "{\"code\":\"" + coupon + "\",\"type\":\"FIXED\",\"value\":10,"
                + "\"minOrderAmount\":1000000,\"totalQuantity\":1,\"validFrom\":\"" + Instant.now().minusSeconds(60)
                + "\",\"validUntil\":\"" + Instant.now().plusSeconds(3600) + "\"}");
        Res couponFirst = order(user, uniq(), items(p, 1), "\"couponCode\":\"" + coupon + "\",\"usePoints\":999999");
        assertThat(couponFirst.status()).isEqualTo(409);
        assertThat(couponFirst.code()).isEqualTo("COUPON_NOT_APPLICABLE");

        // all-or-nothing (R3.4): the failed orders reserved nothing and spent no points
        JsonNode prod = getProduct(p);
        assertThat(prod.get("reserved").asLong()).isEqualTo(2 + 1 + 1);
        assertThat(balance(user)).isEqualTo(3300);

        // P2.7: same key, only usePoints differs -> different request
        String key = uniq();
        Res first = order(user, key, items(p, 1), "\"usePoints\":10");
        assertThat(first.status()).isEqualTo(201);
        assertThat(order(user, key, items(p, 1), "\"usePoints\":10").body()).isEqualTo(first.body());
        assertThat(order(user, key, items(p, 1), "\"usePoints\":11").status()).isEqualTo(422);
        assertThat(order(user, key, items(p, 1), "\"usePoints\":11").code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(order(user, key, items(p, 1), "\"usePoints\":0").status()).isEqualTo(422);
        assertThat(order(user, key, items(p, 1), null).status()).isEqualTo(422);
        assertThat(balance(user)).isEqualTo(3290);
    }

    @Test
    void p2PointsComeBackWhenOrderEnds() throws Exception {
        String user = "p2b-" + uniq();
        long p = product(1000, 10);
        grant(user, 1000);

        long cancelled = orderWithPoints(user, items(p, 1), 400).num("id");
        assertThat(balance(user)).isEqualTo(600);
        assertThat(cancel(cancelled).body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(balance(user)).isEqualTo(1000);

        long declined = orderWithPoints(user, items(p, 1), 300).num("id");
        assertThat(pay(declined, uniq(), "decline").status()).isEqualTo(402);
        assertThat(getOrder(declined).body().get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(balance(user)).isEqualTo(1000);

        // pending and paid orders keep their points out of the balance
        long pending = orderWithPoints(user, items(p, 1), 200).num("id");
        long paid = orderWithPoints(user, items(p, 1), 100).num("id");
        assertThat(pay(paid, uniq(), "ok").status()).isEqualTo(200);
        assertThat(balance(user)).isEqualTo(700);

        // expiry (TTL 5s): points are back within 2s after expiresAt, visible on the balance read itself
        Instant expiresAt = Instant.parse(getOrder(pending).body().get("expiresAt").asText());
        long wait = expiresAt.toEpochMilli() + 1500 - System.currentTimeMillis();
        Thread.sleep(Math.max(wait, 0));
        assertThat(balance(user)).isEqualTo(900);
        assertThat(getOrder(pending).body().get("status").asText()).isEqualTo("EXPIRED");
    }

    // ------------------------------------------------------------------------------------------------- P3

    @Test
    void p3PaymentAmountIsCardAmount() throws Exception {
        String user = "p3-" + uniq();
        long p = product(1000, 10);
        grant(user, 5000);

        long o1 = orderWithPoints(user, items(p, 2), 700).num("id");
        assertThat(pay(o1, uniq(), "ok").status()).isEqualTo(200);
        PgCall charged = pgCalls.stream().filter(c -> c.path().equals("/v1/payments") && c.orderId().equals("" + o1))
                .findFirst().orElseThrow();
        assertThat(charged.amount()).isEqualTo(1300);

        // cardAmount 0: approved without the PG
        long o2 = orderWithPoints(user, items(p, 2), 2000).num("id");
        int before = pgCalls.size();
        Res free = pay(o2, uniq(), "fail"); // would be a 503 if the PG were called
        assertThat(free.status()).isEqualTo(200);
        assertThat(free.body().get("status").asText()).isEqualTo("PAID");
        assertThat(free.body().get("paidAt").isNull()).isFalse();
        assertThat(pgCalls.size()).isEqualTo(before);
        assertThat(getProduct(p).get("reserved").asLong()).isZero();

        // PG outage: nothing changes, points stay spent
        long o3 = orderWithPoints(user, items(p, 1), 500).num("id");
        long balanceBefore = balance(user);
        assertThat(pay(o3, uniq(), "fail").status()).isEqualTo(503);
        assertThat(getOrder(o3).body().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(balance(user)).isEqualTo(balanceBefore);
        // decline: PAYMENT_FAILED + points back
        assertThat(pay(o3, uniq(), "decline").status()).isEqualTo(402);
        assertThat(balance(user)).isEqualTo(balanceBefore + 500);
    }

    // ------------------------------------------------------------------------------------------------- P4

    @Test
    void p4PartialRefundProratesCumulativelyAndRefundsCardFirst() throws Exception {
        // subtotal 1666, coupon -100 -> total 1566, points 500 -> card 1066
        Paid o = paidOrder(1000, 500, true);
        assertThat(balance(o.user())).isEqualTo(500);
        JsonNode paidOrder = getOrder(o.orderId()).body();
        assertThat(paidOrder.get("totalPrice").asLong()).isEqualTo(1566);
        assertThat(paidOrder.get("cardAmount").asLong()).isEqualTo(1066);

        // 1) refund A x1: floor(1566 * 1000 / 1666) = 939, all of it to the card
        String k1 = uniq();
        Res r1 = refund(o.orderId(), k1, items(o.a(), 1));
        assertThat(r1.status()).isEqualTo(200);
        assertThat(r1.body().get("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(r1.num("refundedAmount")).isEqualTo(939);
        assertThat(r1.body().get("items").get(0).get("refundedQuantity").asLong()).isEqualTo(1);
        assertThat(r1.body().get("items").get(1).get("refundedQuantity").asLong()).isZero();
        PgCall c1 = refundCall(k1);
        assertThat(c1.amount()).isEqualTo(939);
        assertThat(c1.path()).contains("/refund");
        assertThat(balance(o.user())).isEqualTo(500);
        assertThat(getProduct(o.a()).get("stock").asLong()).isEqualTo(100); // 99 sold + 1 back
        assertThat(getProduct(o.b()).get("stock").asLong()).isEqualTo(98);
        assertThat(call("GET", "/api/coupons/" + o.couponCode(), null).num("usedCount")).isEqualTo(1);

        // 2) refund B x1: cumulative floor(1566 * 1333 / 1666) = 1252 -> this refund 313 = card 127 + points 186
        String k2 = uniq();
        Res r2 = refund(o.orderId(), k2, items(o.b(), 1));
        assertThat(r2.num("refundedAmount")).isEqualTo(1252);
        assertThat(refundCall(k2).amount()).isEqualTo(127);
        assertThat(balance(o.user())).isEqualTo(686);

        // 3) refund B x1 again: cumulative 1566 -> 314, card exhausted -> all points, no PG call
        String k3 = uniq();
        Res r3 = refund(o.orderId(), k3, items(o.b(), 1));
        assertThat(r3.status()).isEqualTo(200);
        assertThat(r3.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(r3.num("refundedAmount")).isEqualTo(1566);
        assertThat(pgCalls.stream().anyMatch(c -> k3.equals(c.idempotencyKey()))).isFalse();
        assertThat(balance(o.user())).isEqualTo(1000);
        assertThat(getProduct(o.a()).get("stock").asLong()).isEqualTo(100);
        assertThat(getProduct(o.b()).get("stock").asLong()).isEqualTo(100);
        assertThat(call("GET", "/api/coupons/" + o.couponCode(), null).num("usedCount")).isZero();
        assertThat(pgRefundSum(List.of(k1, k2, k3))).isEqualTo(1066);

        // idempotent replay returns the first response without touching anything
        int calls = pgCalls.size();
        assertThat(refund(o.orderId(), k1, items(o.a(), 1)).body()).isEqualTo(r1.body());
        assertThat(pgCalls.size()).isEqualTo(calls);
        assertThat(refund(o.orderId(), k1, items(o.a(), 2)).status()).isEqualTo(422);
        assertThat(refund(o.orderId(), k1, items(o.a(), 2)).code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(balance(o.user())).isEqualTo(1000);
        // the key space is independent of pay (same key as the payment is a fresh key here)
        assertThat(refund(o.orderId(), o.payKey(), items(o.a(), 1)).code()).isEqualTo("INVALID_STATE");
    }

    PgCall refundCall(String key) {
        return pgCalls.stream().filter(c -> c.path().endsWith("/refund") && key.equals(c.idempotencyKey()))
                .findFirst().orElseThrow();
    }

    @Test
    void p4Validation() throws Exception {
        Paid o = paidOrder(0, 0, false);
        String path = "/api/orders/" + o.orderId() + "/refunds";
        String k = uniq();
        String[] badBodies = {"{\"items\":[]}", "{}", "{\"items\":[{\"productId\":" + o.a() + ",\"quantity\":0}]}",
                "{\"items\":[{\"productId\":" + o.a() + ",\"quantity\":1},{\"productId\":" + o.a()
                        + ",\"quantity\":1}]}",
                "{\"items\":[{\"quantity\":1}]}", "{bad", "{\"items\":[" + "{\"productId\":1,\"quantity\":1},"
                        .repeat(20) + "{\"productId\":2,\"quantity\":1}]}"};
        for (String bad : badBodies) {
            Res r = call("POST", path, bad, "Idempotency-Key", k);
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(r.contentType()).startsWith("application/problem+json");
        }
        assertThat(call("POST", path, "{\"items\":" + items(o.a(), 1) + "}").status()).isEqualTo(400); // no header
        assertThat(call("POST", path, "{\"items\":" + items(o.a(), 1) + "}", "Idempotency-Key", "k".repeat(65))
                .status()).isEqualTo(400);

        assertThat(refund(999_999_999L, uniq(), items(o.a(), 1)).code()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(refund(999_999_999L, uniq(), items(o.a(), 1)).status()).isEqualTo(404);

        // 409 REFUND_QUANTITY_EXCEEDED: unknown product, too many, already refunded
        long other = product(10, 1);
        assertThat(refund(o.orderId(), uniq(), items(other, 1)).code()).isEqualTo("REFUND_QUANTITY_EXCEEDED");
        assertThat(refund(o.orderId(), uniq(), items(o.b(), 3)).code()).isEqualTo("REFUND_QUANTITY_EXCEEDED");
        assertThat(refund(o.orderId(), uniq(), items(o.a(), 1, o.b(), 3)).status()).isEqualTo(409);
        // all-or-nothing: nothing was refunded by the rejected requests
        assertThat(getOrder(o.orderId()).body().get("refundedAmount").asLong()).isZero();
        assertThat(getOrder(o.orderId()).body().get("status").asText()).isEqualTo("PAID");
        assertThat(refund(o.orderId(), uniq(), items(o.b(), 2)).status()).isEqualTo(200);
        assertThat(refund(o.orderId(), uniq(), items(o.b(), 1)).code()).isEqualTo("REFUND_QUANTITY_EXCEEDED");

        // INVALID_STATE comes before REFUND_QUANTITY_EXCEEDED
        long p = product(100, 5);
        long pending = order("v-" + uniq(), uniq(), items(p, 1), null).num("id");
        Res notPaid = refund(pending, uniq(), items(p, 99));
        assertThat(notPaid.status()).isEqualTo(409);
        assertThat(notPaid.code()).isEqualTo("INVALID_STATE");
    }

    @Test
    void p4GatewayOutageChangesNothing() throws Exception {
        Paid o = paidOrder(1000, 500, true);
        JsonNode before = getOrder(o.orderId()).body();
        refundMode.set("fail");
        try {
            Res r = refund(o.orderId(), uniq(), items(o.a(), 1));
            assertThat(r.status()).isEqualTo(503);
            assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        } finally {
            refundMode.set("ok");
        }
        assertThat(getOrder(o.orderId()).body()).isEqualTo(before);
        assertThat(getProduct(o.a()).get("stock").asLong()).isEqualTo(99);
        assertThat(balance(o.user())).isEqualTo(500);
        assertThat(call("GET", "/api/coupons/" + o.couponCode(), null).num("usedCount")).isEqualTo(1);
        // the failed key can be retried with the same request
        assertThat(refund(o.orderId(), uniq(), items(o.a(), 1)).status()).isEqualTo(200);
    }

    @Test
    void p4PointsOnlyOrderNeverCallsGateway() throws Exception {
        Paid o = paidOrder(5000, 1566 + 100, false); // total 1666, fully paid with points
        assertThat(getOrder(o.orderId()).num("cardAmount")).isZero();
        int calls = pgCalls.size();
        Res r = refund(o.orderId(), uniq(), items(o.a(), 1));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.num("refundedAmount")).isEqualTo(1000);
        assertThat(balance(o.user())).isEqualTo(5000 - 1666 + 1000);
        assertThat(pgCalls.size()).isEqualTo(calls);
    }

    // ------------------------------------------------------------------------------------------------- P5

    @Test
    void p5CancelAfterPartialRefundRefundsTheRest() throws Exception {
        Paid o = paidOrder(1000, 500, true);
        assertThat(refund(o.orderId(), uniq(), items(o.a(), 1)).num("refundedAmount")).isEqualTo(939);

        Res c = cancel(o.orderId());
        assertThat(c.status()).isEqualTo(200);
        assertThat(c.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(c.num("refundedAmount")).isEqualTo(1566);
        assertThat(refundCall("cancel-" + o.orderId()).amount()).isEqualTo(1066 - 939);
        assertThat(balance(o.user())).isEqualTo(1000);
        assertThat(getProduct(o.a()).get("stock").asLong()).isEqualTo(100);
        assertThat(getProduct(o.b()).get("stock").asLong()).isEqualTo(100);
        assertThat(call("GET", "/api/coupons/" + o.couponCode(), null).num("usedCount")).isZero();
        assertThat(cancel(o.orderId()).code()).isEqualTo("INVALID_STATE");
        assertThat(refund(o.orderId(), uniq(), items(o.a(), 1)).code()).isEqualTo("INVALID_STATE");
    }

    @Test
    void p5CancelPaidOrderAndGatewayOutage() throws Exception {
        Paid o = paidOrder(1000, 500, false); // total 1666, card 1166
        refundMode.set("fail");
        try {
            assertThat(cancel(o.orderId()).status()).isEqualTo(503);
        } finally {
            refundMode.set("ok");
        }
        JsonNode untouched = getOrder(o.orderId()).body();
        assertThat(untouched.get("status").asText()).isEqualTo("PAID");
        assertThat(untouched.get("refundedAmount").asLong()).isZero();
        assertThat(balance(o.user())).isEqualTo(500);
        assertThat(getProduct(o.a()).get("stock").asLong()).isEqualTo(99);

        Res c = cancel(o.orderId());
        assertThat(c.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(refundCall("cancel-" + o.orderId()).amount()).isEqualTo(1166);
        assertThat(balance(o.user())).isEqualTo(1000);
    }

    @Test
    void p5ShipAfterPartialRefundThenNoRefundOrCancel() throws Exception {
        Paid o = paidOrder(0, 0, false);
        assertThat(refund(o.orderId(), uniq(), items(o.b(), 1)).body().get("status").asText())
                .isEqualTo("PARTIALLY_REFUNDED");

        Res listed = call("GET", "/api/orders?userId=" + o.user() + "&status=PARTIALLY_REFUNDED", null);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body().get("content")).hasSize(1);
        assertThat(listed.body().get("content").get(0).get("id").asLong()).isEqualTo(o.orderId());

        assertThat(call("POST", "/api/orders/" + o.orderId() + "/deliver", null).status()).isEqualTo(409);
        Res shipped = call("POST", "/api/orders/" + o.orderId() + "/ship", null);
        assertThat(shipped.status()).isEqualTo(200);
        assertThat(shipped.body().get("status").asText()).isEqualTo("SHIPPED");
        Res noRefund = refund(o.orderId(), uniq(), items(o.a(), 1));
        assertThat(noRefund.status()).isEqualTo(409);
        assertThat(noRefund.code()).isEqualTo("INVALID_STATE");
        assertThat(cancel(o.orderId()).code()).isEqualTo("INVALID_STATE");
        assertThat(call("POST", "/api/orders/" + o.orderId() + "/deliver", null).status()).isEqualTo(200);
        assertThat(refund(o.orderId(), uniq(), items(o.a(), 1)).code()).isEqualTo("INVALID_STATE");
        assertThat(cancel(o.orderId()).code()).isEqualTo("INVALID_STATE");
    }

    // ------------------------------------------------------------------------------------------------- P6

    @Test
    void p6ConcurrentPointUse() throws Exception {
        String user = "p6a-" + uniq();
        long p = product(1000, 100);
        grant(user, 1000);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> orderWithPoints(user, items(p, 1), 600).status());
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(4);
        assertThat(balance(user)).isEqualTo(400);
        assertThat(getProduct(p).get("reserved").asLong()).isEqualTo(1);
    }

    @Test
    void p6ConcurrentPartialRefunds() throws Exception {
        String user = "p6b-" + uniq();
        long p = product(1000, 100);
        long q = product(1000, 100); // stays unrefunded so the order is still PARTIALLY_REFUNDED (not INVALID_STATE)
        grant(user, 2000);
        long id = orderWithPoints(user, items(p, 3, q, 1), 2000).num("id"); // total 4000, card 2000
        assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);

        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<String> keys = new ArrayList<>();
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String key = uniq();
            keys.add(key);
            tasks.add(() -> refund(id, key, items(p, 1)));
        }
        List<Res> results = new ArrayList<>();
        for (Future<Res> f : pool.invokeAll(tasks)) {
            results.add(f.get());
        }
        pool.shutdown();
        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(3);
        List<Res> rejected = results.stream().filter(r -> r.status() == 409).toList();
        assertThat(rejected).hasSize(2);
        assertThat(rejected).allMatch(r -> r.code().equals("REFUND_QUANTITY_EXCEEDED"));

        JsonNode fin = getOrder(id).body();
        assertThat(fin.get("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(fin.get("items").get(0).get("refundedQuantity").asLong()).isEqualTo(3);
        assertThat(fin.get("items").get(1).get("refundedQuantity").asLong()).isZero();
        assertThat(fin.get("refundedAmount").asLong()).isEqualTo(3000);
        assertThat(pgRefundSum(keys)).isEqualTo(2000); // = the order's cumulative card refund
        assertThat(getProduct(p).get("stock").asLong()).isEqualTo(100);
        assertThat(getProduct(q).get("stock").asLong()).isEqualTo(99);
        assertThat(balance(user)).isEqualTo(1000); // the third refund went back as points
    }

    @Test
    void p6ConcurrentCancelAndPartialRefund() throws Exception {
        for (int round = 0; round < 3; round++) {
            String user = "p6c-" + uniq();
            long p = product(1000, 100);
            grant(user, 600);
            long id = orderWithPoints(user, items(p, 3), 600).num("id"); // total 3000, card 2400
            assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);

            String refundKey = uniq();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Callable<Res>> tasks = List.of(() -> cancel(id), () -> refund(id, refundKey, items(p, 2)));
            List<Res> results = new ArrayList<>();
            for (Future<Res> f : pool.invokeAll(tasks)) {
                results.add(f.get());
            }
            pool.shutdown();
            assertThat(results).allMatch(r -> r.status() < 500);
            assertThat(results.get(0).status()).isEqualTo(200); // cancel always ends up refunding the rest
            assertThat(results.get(1).status()).isIn(200, 409);

            JsonNode fin = getOrder(id).body();
            assertThat(fin.get("status").asText()).isEqualTo("REFUNDED");
            assertThat(fin.get("items").get(0).get("refundedQuantity").asLong()).isEqualTo(3);
            assertThat(fin.get("refundedAmount").asLong()).isEqualTo(3000);
            assertThat(getProduct(p).get("stock").asLong()).isEqualTo(100);
            assertThat(balance(user)).isEqualTo(600);
            assertThat(pgRefundSum(List.of(refundKey, "cancel-" + id))).isEqualTo(2400);
        }
    }
}
