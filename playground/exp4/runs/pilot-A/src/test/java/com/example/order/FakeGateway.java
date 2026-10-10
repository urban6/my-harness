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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** 테스트용 외부 PG. 계약(POST /v1/payments, POST /v1/payments/{id}/refund)을 흉내 낸다. */
public class FakeGateway {

    public enum Mode { NORMAL, SERVER_ERROR, HANG, DROP_CONNECTION }

    public record Received(String method, String path, Map<String, String> headers, JsonNode body) {
    }

    public static final String DECLINE_TOKEN = "tok_decline";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> chargesByKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private volatile Mode mode = Mode.NORMAL;
    private volatile long delayMillis = 0;

    public FakeGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        mode = Mode.NORMAL;
        delayMillis = 0;
        requests.clear();
        chargesByKey.clear();
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public void delay(long millis) {
        this.delayMillis = millis;
    }

    public List<Received> requests() {
        return requests;
    }

    public List<Received> charges() {
        return requests.stream().filter(r -> r.path().equals("/v1/payments")).toList();
    }

    public List<Received> refunds() {
        return requests.stream().filter(r -> r.path().endsWith("/refund")).toList();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        byte[] raw = ex.getRequestBody().readAllBytes();
        JsonNode body = raw.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
        Map<String, String> headers = new ConcurrentHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        requests.add(new Received(ex.getRequestMethod(), path, headers, body));

        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        switch (mode) {
            case SERVER_ERROR -> {
                reply(ex, 500, "{\"error\":\"boom\"}");
                return;
            }
            case HANG -> {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                reply(ex, 200, "{\"paymentId\":\"late\",\"status\":\"APPROVED\"}");
                return;
            }
            case DROP_CONNECTION -> {
                ex.close();
                return;
            }
            default -> { }
        }

        if (path.equals("/v1/payments")) {
            String key = headers.getOrDefault("idempotency-key", "");
            String json = chargesByKey.computeIfAbsent(key, k -> {
                boolean decline = DECLINE_TOKEN.equals(body.path("cardToken").asText());
                return "{\"paymentId\":\"pay_" + sequence.incrementAndGet() + "\",\"status\":\""
                        + (decline ? "DECLINED" : "APPROVED") + "\"}";
            });
            reply(ex, 200, json);
        } else if (path.startsWith("/v1/payments/") && path.endsWith("/refund")) {
            String id = path.substring("/v1/payments/".length(), path.length() - "/refund".length());
            reply(ex, 200, "{\"paymentId\":\"" + id + "\",\"status\":\"REFUNDED\"}");
        } else {
            reply(ex, 404, "{}");
        }
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
