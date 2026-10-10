package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** 테스트용 PG. 외부 PG 계약(결제·환불)을 흉내 내고, 동작 모드와 받은 요청을 기록한다. */
public final class FakePaymentGateway {

    public enum Mode { APPROVE, DECLINE, SERVER_ERROR, SLOW }

    public record PaymentCall(String idempotencyKey, JsonNode body) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SLOW_MILLIS = 3_000;

    private final HttpServer server;
    private final Map<String, String> responsesByKey = new ConcurrentHashMap<>();
    private final List<PaymentCall> paymentCalls = new CopyOnWriteArrayList<>();
    private final List<String> refundCalls = new CopyOnWriteArrayList<>();
    private volatile Mode paymentMode = Mode.APPROVE;
    private volatile Mode refundMode = Mode.APPROVE;
    private volatile long paymentDelayMillis;

    private FakePaymentGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/payments", this::handle);
        server.start();
    }

    public static FakePaymentGateway start() {
        try {
            return new FakePaymentGateway();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        paymentMode = Mode.APPROVE;
        refundMode = Mode.APPROVE;
        paymentDelayMillis = 0;
        paymentCalls.clear();
        refundCalls.clear();
    }

    public void paymentMode(Mode mode) {
        this.paymentMode = mode;
    }

    public void refundMode(Mode mode) {
        this.refundMode = mode;
    }

    public void paymentDelayMillis(long millis) {
        this.paymentDelayMillis = millis;
    }

    public List<PaymentCall> paymentCalls() {
        return List.copyOf(paymentCalls);
    }

    public List<String> refundCalls() {
        return List.copyOf(refundCalls);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/refund")) {
                String paymentId = path.substring("/v1/payments/".length(), path.length() - "/refund".length());
                refundCalls.add(paymentId);
                respond(exchange, refundMode, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
                return;
            }
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            paymentCalls.add(new PaymentCall(key, body));
            if (paymentDelayMillis > 0) {
                sleep(paymentDelayMillis);
            }
            Mode mode = paymentMode;
            if (mode == Mode.SERVER_ERROR || mode == Mode.SLOW) {
                respond(exchange, mode, "");
                return;
            }
            String response = responsesByKey.computeIfAbsent(key, k -> "{\"paymentId\":\"pay_" + UUID.randomUUID()
                    + "\",\"status\":\"" + (mode == Mode.APPROVE ? "APPROVED" : "DECLINED") + "\"}");
            respond(exchange, Mode.APPROVE, response);
        }
    }

    private static void respond(HttpExchange exchange, Mode mode, String okBody) throws IOException {
        if (mode == Mode.SLOW) {
            sleep(SLOW_MILLIS);
        }
        if (mode == Mode.SERVER_ERROR) {
            exchange.sendResponseHeaders(500, -1);
            return;
        }
        byte[] bytes = okBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
