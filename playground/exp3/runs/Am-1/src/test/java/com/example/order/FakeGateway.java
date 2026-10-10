package com.example.order;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal in-process stand-in for the external PG. */
public class FakeGateway {

    private final HttpServer server;
    public volatile String chargeStatus = "APPROVED";
    public volatile boolean failing = false;
    public volatile long delayMillis = 0;
    public final AtomicInteger chargeCalls = new AtomicInteger();
    public final AtomicInteger refundCalls = new AtomicInteger();
    public final List<String> chargeKeys = new CopyOnWriteArrayList<>();
    private final Map<String, String> byKey = new ConcurrentHashMap<>();

    public FakeGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/v1/payments", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        chargeStatus = "APPROVED";
        failing = false;
        delayMillis = 0;
        chargeCalls.set(0);
        refundCalls.set(0);
        chargeKeys.clear();
        byKey.clear();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            ex.getRequestBody().readAllBytes();
            if (delayMillis > 0) Thread.sleep(delayMillis);
            if (failing) {
                reply(ex, 503, "{}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/refund")) {
                refundCalls.incrementAndGet();
                String id = path.split("/")[3];
                reply(ex, 200, "{\"paymentId\":\"" + id + "\",\"status\":\"REFUNDED\"}");
            } else {
                chargeCalls.incrementAndGet();
                String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
                chargeKeys.add(key);
                String id = byKey.computeIfAbsent(key, k -> "pay-" + java.util.UUID.randomUUID());
                reply(ex, 200, "{\"paymentId\":\"" + id + "\",\"status\":\"" + chargeStatus + "\"}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
