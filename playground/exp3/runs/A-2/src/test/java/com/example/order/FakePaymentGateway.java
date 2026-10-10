package com.example.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 테스트용 외부 PG. cardToken 값으로 동작이 결정된다. */
final class FakePaymentGateway {

    record Call(String path, String idempotencyKey, JsonNode body) {
    }

    static final String DECLINE = "tok_decline";
    static final String ERROR = "tok_error";
    static final String SLOW = "tok_slow";
    static final String NO_REFUND = "tok_norefund";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Queue<Call> calls = new ConcurrentLinkedQueue<>();
    private final Map<Long, String> paymentIds = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final HttpServer server;

    FakePaymentGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/payments", this::handle);
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    List<Call> callsFor(String pathSuffix, long orderId) {
        return calls.stream()
                .filter(c -> c.path().endsWith(pathSuffix) && c.body().path("orderId").asLong(-1) == orderId)
                .toList();
    }

    List<Call> charges(long orderId) {
        return callsFor("/v1/payments", orderId);
    }

    String paymentIdFor(long orderId) {
        return paymentIds.get(orderId);
    }

    long refundCount(String paymentId) {
        return calls.stream().filter(c -> c.path().equals("/v1/payments/" + paymentId + "/refund")).count();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode body = raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
        calls.add(new Call(path, ex.getRequestHeaders().getFirst("Idempotency-Key"), body));

        if (path.endsWith("/refund")) {
            String paymentId = path.substring("/v1/payments/".length(), path.length() - "/refund".length());
            if (paymentId.contains(NO_REFUND)) {
                reply(ex, 500, "{\"error\":\"boom\"}");
            } else {
                reply(ex, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
            }
            return;
        }
        String token = body.path("cardToken").asText();
        if (SLOW.equals(token)) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ERROR.equals(token)) {
            reply(ex, 500, "{\"error\":\"boom\"}");
            return;
        }
        String status = DECLINE.equals(token) ? "DECLINED" : "APPROVED";
        String paymentId = "pay-" + (NO_REFUND.equals(token) ? NO_REFUND + "-" : "") + seq.incrementAndGet();
        paymentIds.put(body.path("orderId").asLong(), paymentId);
        reply(ex, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"" + status + "\"}");
    }

    private void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
