package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// close the context with this class: its expiry sweeper must not outlive the container it talks to
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OrderPaymentFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final FakePaymentGateway pg = new FakePaymentGateway();

    @AfterAll
    static void stopPg() {
        pg.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("PAYMENT_GATEWAY_URL", () -> "http://127.0.0.1:" + pg.port());
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
        pg.setRefundMode("fail");
        assertThat(call("POST", "/api/orders/" + o + "/cancel", null).status()).isEqualTo(503);
        pg.setRefundMode("ok");
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
        int before = pg.paymentCount();
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
        assertThat(pg.paymentCount() - before).isEqualTo(1);
    }

    @Test
    void expiryAndValidationAndListing() throws Exception {
        long p = product(500, 5);
        String user = "list-" + uniq();
        long first = order(user, uniq(), "{\"items\":[{\"productId\":" + p + ",\"quantity\":3}]}").body()
                .get("id").asLong();
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
        assertThat(call("GET", "/api/orders/" + first, null).body().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(call("GET", "/api/products/" + p, null).body().get("reserved").asLong()).isZero();
    }
}
