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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared by the test classes. They run in one Spring context, so the PostgreSQL container and the PG stub live for the
 * whole JVM (started once, never stopped per class). Calls default to tenant {@link #DEFAULT_TENANT}.
 */
abstract class IntegrationTestBase {

    static final String DEFAULT_TENANT = "default";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** PG mock: behaviour chosen by cardToken (ok / decline / fail / slow); refund mode switchable. */
    static final HttpServer pg;
    static final AtomicInteger paymentCalls = new AtomicInteger();
    static final AtomicReference<String> refundMode = new AtomicReference<>("ok");
    /** Every payment request the PG received: its Idempotency-Key header and JSON body. */
    static final List<PgCall> pgCalls = new CopyOnWriteArrayList<>();

    record PgCall(String idempotencyKey, String body) {
    }

    static {
        POSTGRES.start();
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
                    out = "{\"paymentId\":\"" + id + "\",\"status\":\"REFUNDED\"}";
                }
            } else {
                paymentCalls.incrementAndGet();
                pgCalls.add(new PgCall(ex.getRequestHeaders().getFirst("Idempotency-Key"), body));
                String token = om.readTree(body).path("cardToken").asText();
                String paymentId = "pay-" + UUID.randomUUID();
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

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("PAYMENT_GATEWAY_URL", () -> "http://127.0.0.1:" + pg.getAddress().getPort());
        r.add("ORDER_PAYMENT_TTL", () -> "PT6S");
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper om = new ObjectMapper();

    record Res(int status, JsonNode body, String contentType, String location) {
    }

    /** Sends as {@link #DEFAULT_TENANT}. */
    Res call(String method, String path, String body, String... headers) throws Exception {
        return send(method, path, body, DEFAULT_TENANT, headers);
    }

    /** Sends with the given X-Tenant-Id; a null tenant sends no tenant header at all. */
    Res send(String method, String path, String body, String tenant, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
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
        return product(DEFAULT_TENANT, price, stock);
    }

    long product(String tenant, long price, long stock) throws Exception {
        Res r = send("POST", "/api/products", "{\"name\":\"p\",\"price\":" + price + ",\"stock\":" + stock + "}",
                tenant);
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    Res order(String user, String key, String body) throws Exception {
        return orderAs(DEFAULT_TENANT, user, key, body);
    }

    Res orderAs(String tenant, String user, String key, String body) throws Exception {
        return send("POST", "/api/orders", body, tenant, "X-User-Id", user, "Idempotency-Key", key);
    }

    Res pay(long orderId, String key, String token) throws Exception {
        return payAs(DEFAULT_TENANT, orderId, key, token);
    }

    Res payAs(String tenant, long orderId, String key, String token) throws Exception {
        return send("POST", "/api/orders/" + orderId + "/pay", "{\"cardToken\":\"" + token + "\"}", tenant,
                "Idempotency-Key", key);
    }

    /** RATE 10% coupon, valid now, no minimum order amount. */
    Res couponAs(String tenant, String code, long totalQuantity) throws Exception {
        String now = Instant.now().minusSeconds(60).toString();
        String later = Instant.now().plusSeconds(3600).toString();
        return send("POST", "/api/coupons", "{\"code\":\"" + code + "\",\"type\":\"RATE\",\"value\":10,"
                + "\"maxDiscountAmount\":5000000000,\"totalQuantity\":" + totalQuantity + ",\"validFrom\":\"" + now
                + "\",\"validUntil\":\"" + later + "\"}", tenant);
    }

    String uniq() {
        return UUID.randomUUID().toString();
    }
}
