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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared infrastructure: one PostgreSQL container and one PG mock for the whole test run (so the Spring context is
 * cached across test classes), plus HTTP helpers. Calls carry {@code X-Tenant-Id: main} unless a tenant header is
 * passed explicitly or {@link #rawCall} is used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationTestBase {

    static final String DEFAULT_TENANT = "main";

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** PG mock: behaviour chosen by cardToken (ok / decline / fail / slow); refund mode switchable. */
    static final HttpServer pg;
    static final AtomicInteger paymentCalls = new AtomicInteger();
    /** Idempotency-Key header of every payment request the PG mock has received. */
    static final List<String> pgPaymentKeys = new CopyOnWriteArrayList<>();
    static final AtomicReference<String> refundMode = new AtomicReference<>("ok");

    static {
        postgres.start();
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
                pgPaymentKeys.add(ex.getRequestHeaders().getFirst("Idempotency-Key"));
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
        Runtime.getRuntime().addShutdownHook(new Thread(() -> pg.stop(0)));
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
        r.add("PAYMENT_GATEWAY_URL", () -> "http://127.0.0.1:" + pg.getAddress().getPort());
        r.add("ORDER_PAYMENT_TTL", () -> "PT6S");
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper om = new ObjectMapper();

    record Res(int status, JsonNode body, String contentType, String location) {
    }

    /** Sends the request exactly as given (no tenant header is added). */
    Res rawCall(String method, String path, String body, String... headers) throws Exception {
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

    /** Same as {@link #rawCall} but as {@code tenant}. */
    Res callAs(String tenant, String method, String path, String body, String... headers) throws Exception {
        String[] all = new String[headers.length + 2];
        all[0] = "X-Tenant-Id";
        all[1] = tenant;
        System.arraycopy(headers, 0, all, 2, headers.length);
        return rawCall(method, path, body, all);
    }

    /** Same as {@link #rawCall} but as the default tenant. */
    Res call(String method, String path, String body, String... headers) throws Exception {
        return callAs(DEFAULT_TENANT, method, path, body, headers);
    }

    long product(String tenant, long price, long stock) throws Exception {
        Res r = callAs(tenant, "POST", "/api/products",
                "{\"name\":\"p\",\"price\":" + price + ",\"stock\":" + stock + "}");
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    long product(long price, long stock) throws Exception {
        return product(DEFAULT_TENANT, price, stock);
    }

    Res order(String tenant, String user, String key, String body) throws Exception {
        return callAs(tenant, "POST", "/api/orders", body, "X-User-Id", user, "Idempotency-Key", key);
    }

    Res order(String user, String key, String body) throws Exception {
        return order(DEFAULT_TENANT, user, key, body);
    }

    Res pay(String tenant, long orderId, String key, String token) throws Exception {
        return callAs(tenant, "POST", "/api/orders/" + orderId + "/pay", "{\"cardToken\":\"" + token + "\"}",
                "Idempotency-Key", key);
    }

    Res pay(long orderId, String key, String token) throws Exception {
        return pay(DEFAULT_TENANT, orderId, key, token);
    }

    String uniq() {
        return UUID.randomUUID().toString();
    }
}
