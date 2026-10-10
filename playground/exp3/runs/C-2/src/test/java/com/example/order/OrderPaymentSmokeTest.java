package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Thin smoke test (Phase 2): context loads, product create/get, order -> pay happy path, error format.
 * Phase 3 test-writer extends coverage.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "order.expiry-sweep-enabled=false")
class OrderPaymentSmokeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final HttpServer PG_STUB = startPgStub();

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        registry.add("payment.gateway.url", () -> "http://127.0.0.1:" + PG_STUB.getAddress().getPort());
    }

    @Autowired
    TestRestTemplate rest;

    @AfterAll
    static void stopStub() {
        PG_STUB.stop(0);
    }

    @Test
    void productCreateAndGet() {
        ResponseEntity<JsonNode> created = post("/api/products", Map.of("name", "keyboard", "price", 30000, "stock", 10),
                null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getBody().get("available").asInt()).isEqualTo(10);

        ResponseEntity<JsonNode> fetched = rest.getForEntity(created.getHeaders().getLocation(), JsonNode.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("name").asText()).isEqualTo("keyboard");
    }

    @Test
    void unknownProductIsProblemDetails() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/products/999999", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody().get("type").asText()).isEqualTo("https://example.com/problems/product-not-found");
        assertThat(response.getBody().get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void orderThenPayHappyPath() {
        long productId = post("/api/products", Map.of("name", "mouse", "price", 1000, "stock", 5), null)
                .getBody().get("id").asLong();

        HttpHeaders orderHeaders = new HttpHeaders();
        orderHeaders.add("X-User-Id", "u-smoke");
        orderHeaders.add("Idempotency-Key", "order-key-1");
        Map<String, Object> orderBody = Map.of("items", java.util.List.of(Map.of("productId", productId, "quantity", 2)));
        ResponseEntity<JsonNode> order = post("/api/orders", orderBody, orderHeaders);
        assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(order.getBody().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.getBody().get("totalPrice").asLong()).isEqualTo(2000);
        long orderId = order.getBody().get("id").asLong();

        ResponseEntity<JsonNode> replay = post("/api/orders", orderBody, orderHeaders);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("id").asLong()).isEqualTo(orderId);

        assertThat(rest.getForObject("/api/products/" + productId, JsonNode.class).get("reserved").asInt()).isEqualTo(2);

        HttpHeaders payHeaders = new HttpHeaders();
        payHeaders.add("Idempotency-Key", "pay-key-1");
        ResponseEntity<JsonNode> paid = post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok_ok"), payHeaders);
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(paid.getBody().get("paidAt").isNull()).isFalse();

        JsonNode product = rest.getForObject("/api/products/" + productId, JsonNode.class);
        assertThat(product.get("stock").asInt()).isEqualTo(3);
        assertThat(product.get("reserved").asInt()).isEqualTo(0);
    }

    private ResponseEntity<JsonNode> post(String path, Object body, HttpHeaders extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (extra != null) {
            headers.putAll(extra);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static HttpServer startPgStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/payments", exchange -> {
                byte[] response;
                if (exchange.getRequestURI().getPath().endsWith("/refund")) {
                    response = "{\"paymentId\":\"pay-smoke\",\"status\":\"REFUNDED\"}".getBytes(StandardCharsets.UTF_8);
                } else {
                    response = "{\"paymentId\":\"pay-smoke\",\"status\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);
                }
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
