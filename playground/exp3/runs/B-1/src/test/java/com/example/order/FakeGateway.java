package com.example.order;

import java.io.IOException;
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
 * 외부 PG 대역. cardToken 으로 결과를 정한다:
 * tok_decline → DECLINED, tok_error → 500, tok_slow → 응답 지연, 그 외 → APPROVED.
 * 같은 Idempotency-Key 로 다시 오면 처음 응답을 그대로 돌려준다(실제 PG 처럼).
 */
public class FakeGateway {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final AtomicInteger paymentSeq = new AtomicInteger();
    private final Map<String, String> responsesByKey = new ConcurrentHashMap<>();

    public final AtomicInteger chargeCalls = new AtomicInteger();
    public final AtomicInteger refundCalls = new AtomicInteger();
    public final List<String> chargeKeys = new CopyOnWriteArrayList<>();
    public final List<Long> chargedAmounts = new CopyOnWriteArrayList<>();
    public volatile boolean refundFails = false;

    public FakeGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/payments", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        chargeCalls.set(0);
        refundCalls.set(0);
        chargeKeys.clear();
        chargedAmounts.clear();
        responsesByKey.clear();
        refundFails = false;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/refund")) {
                refund(ex, path);
            } else {
                charge(ex);
            }
        } finally {
            ex.close();
        }
    }

    private void charge(HttpExchange ex) throws IOException {
        JsonNode body = mapper.readTree(ex.getRequestBody());
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        chargeCalls.incrementAndGet();
        chargeKeys.add(key);
        chargedAmounts.add(body.get("amount").asLong());
        String token = body.get("cardToken").asText();
        if (token.equals("tok_error")) {
            reply(ex, 500, "{\"error\":\"boom\"}");
            return;
        }
        if (token.equals("tok_slow")) {
            sleep(300);
        }
        String response = responsesByKey.computeIfAbsent(key, k -> {
            String status = token.equals("tok_decline") ? "DECLINED" : "APPROVED";
            return "{\"paymentId\":\"pay_" + paymentSeq.incrementAndGet() + "\",\"status\":\"" + status + "\"}";
        });
        reply(ex, 200, response);
    }

    private void refund(HttpExchange ex, String path) throws IOException {
        refundCalls.incrementAndGet();
        if (refundFails) {
            reply(ex, 500, "{\"error\":\"boom\"}");
            return;
        }
        String paymentId = path.split("/")[3];
        reply(ex, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
    }

    private void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
