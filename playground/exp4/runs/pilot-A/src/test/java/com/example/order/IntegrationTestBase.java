package com.example.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final FakeGateway GATEWAY = new FakeGateway();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // C4의 환경 변수 이름 그대로 application.yml의 자리표시자를 통해 주입한다.
        registry.add("PAYMENT_GATEWAY_URL", GATEWAY::url);
    }

    static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;

    @BeforeEach
    void resetGateway() {
        GATEWAY.reset();
    }

    // ---- HTTP helpers -------------------------------------------------------------------------

    record Res(int status, java.net.http.HttpHeaders headers, String raw) {
        JsonNode json() {
            try {
                return JSON.readTree(raw);
            } catch (IOException e) {
                throw new IllegalStateException("Not JSON: " + raw, e);
            }
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        String code() {
            return json().path("code").asText();
        }
    }

    Res get(String path) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET());
    }

    /** headers는 이름, 값, 이름, 값… 순서. body가 String이면 그대로, 아니면 JSON으로 직렬화한다. */
    Res post(String path, Object body, String... headers) {
        String payload;
        try {
            payload = body == null ? "" : body instanceof String s ? s : JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return send(b);
    }

    private Res send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> r = HTTP.send(builder.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.headers(), r.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- 도메인 helpers -----------------------------------------------------------------------

    static String uniq() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static String couponCode() {
        return uniq().substring(0, 10).toUpperCase();
    }

    long product(long price, long stock) {
        Res r = post("/api/products", Map.of("name", "p-" + uniq().substring(0, 6), "price", price, "stock", stock));
        if (r.status() != 201) {
            throw new IllegalStateException("product create failed: " + r.status() + " " + r.raw());
        }
        return r.json().get("id").asLong();
    }

    JsonNode productOf(long id) {
        return get("/api/products/" + id).json();
    }

    /** FIXED/RATE 쿠폰을 만든다. 유효기간은 현재 기준 어제~내일. */
    String coupon(String type, long value, long minOrder, Long maxDiscount, long totalQuantity) {
        String code = couponCode();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("type", type);
        body.put("value", value);
        body.put("minOrderAmount", minOrder);
        if (maxDiscount != null) {
            body.put("maxDiscountAmount", maxDiscount);
        }
        body.put("totalQuantity", totalQuantity);
        body.put("validFrom", java.time.OffsetDateTime.now().minusDays(1).toString());
        body.put("validUntil", java.time.OffsetDateTime.now().plusDays(1).toString());
        Res r = post("/api/coupons", body);
        if (r.status() != 201) {
            throw new IllegalStateException("coupon create failed: " + r.status() + " " + r.raw());
        }
        return code;
    }

    JsonNode couponOf(String code) {
        return get("/api/coupons/" + code).json();
    }

    static List<Map<String, Object>> items(Object... productIdAndQty) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < productIdAndQty.length; i += 2) {
            list.add(Map.of("productId", productIdAndQty[i], "quantity", productIdAndQty[i + 1]));
        }
        return list;
    }

    Res createOrder(String user, String key, List<Map<String, Object>> items, String couponCode) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("items", items);
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return post("/api/orders", body, "X-User-Id", user, "Idempotency-Key", key);
    }

    /** 새 사용자·새 키로 주문을 만들고 201을 확인한 뒤 본문을 돌려준다. */
    JsonNode order(List<Map<String, Object>> items, String couponCode) {
        Res r = createOrder("u-" + uniq().substring(0, 8), "k-" + uniq(), items, couponCode);
        if (r.status() != 201) {
            throw new IllegalStateException("order create failed: " + r.status() + " " + r.raw());
        }
        return r.json();
    }

    JsonNode order(long productId, int qty) {
        return order(items(productId, qty), null);
    }

    Res pay(long orderId, String token, String key) {
        return post("/api/orders/" + orderId + "/pay", Map.of("cardToken", token), "Idempotency-Key", key);
    }

    Res pay(long orderId) {
        return pay(orderId, "tok_ok", "pay-" + uniq());
    }

    JsonNode paidOrder(long productId, int qty) {
        long id = order(productId, qty).get("id").asLong();
        Res r = pay(id);
        if (r.status() != 200) {
            throw new IllegalStateException("pay failed: " + r.status() + " " + r.raw());
        }
        return r.json();
    }

    JsonNode orderOf(long id) {
        return get("/api/orders/" + id).json();
    }

    /** 모든 작업을 동시에 출발시켜 결과를 모은다. */
    <T> List<T> concurrently(List<Callable<T>> tasks) {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(tasks.size());
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    static long count(List<Res> results, int status) {
        return results.stream().filter(r -> r.status() == status).count();
    }
}
