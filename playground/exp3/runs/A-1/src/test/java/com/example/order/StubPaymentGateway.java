package com.example.order;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 외부 PG 대역. cardToken으로 동작을 고른다:
 * tok_ok → APPROVED, tok_decline → DECLINED, tok_error → HTTP 500.
 * 같은 Idempotency-Key는 최초 응답을 그대로 재생한다.
 */
final class StubPaymentGateway {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final AtomicInteger paymentSeq = new AtomicInteger();
    private final Map<String, String> replayByKey = new ConcurrentHashMap<>();
    final List<String> chargeKeys = new CopyOnWriteArrayList<>();
    final List<String> refundedPaymentIds = new CopyOnWriteArrayList<>();
    final List<JsonNode> chargeBodies = new CopyOnWriteArrayList<>();
    volatile boolean failRefunds;

    StubPaymentGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/payments", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    }

    void start() {
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        chargeKeys.clear();
        refundedPaymentIds.clear();
        chargeBodies.clear();
        replayByKey.clear();
        failRefunds = false;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/v1/payments")) {
                charge(ex);
            } else if (path.endsWith("/refund")) {
                refund(ex, path.substring("/v1/payments/".length(), path.length() - "/refund".length()));
            } else {
                respond(ex, 404, "{}");
            }
        } catch (RuntimeException e) {
            respond(ex, 500, "{}");
        }
    }

    private void charge(HttpExchange ex) throws IOException {
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        JsonNode body = JSON.readTree(ex.getRequestBody());
        chargeKeys.add(key);
        chargeBodies.add(body);
        String replay = key == null ? null : replayByKey.get(key);
        if (replay != null) {
            respond(ex, 200, replay);
            return;
        }
        String token = body.path("cardToken").asText();
        if (token.equals("tok_error")) {
            respond(ex, 500, "{\"error\":\"boom\"}");
            return;
        }
        String status = token.equals("tok_decline") ? "DECLINED" : "APPROVED";
        String response = "{\"paymentId\":\"pay_" + paymentSeq.incrementAndGet() + "\",\"status\":\"" + status + "\"}";
        if (key != null) {
            replayByKey.put(key, response);
        }
        respond(ex, 200, response);
    }

    private void refund(HttpExchange ex, String paymentId) throws IOException {
        ex.getRequestBody().readAllBytes();
        if (failRefunds) {
            respond(ex, 503, "{}");
            return;
        }
        refundedPaymentIds.add(paymentId);
        respond(ex, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
    }

    private void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
