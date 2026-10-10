package com.example.order;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 외부 PG 계약을 흉내 내는 테스트용 HTTP 서버. 응답 방식을 테스트마다 바꿀 수 있다. */
public final class FakePaymentGateway {

    public enum Mode { APPROVE, DECLINE, SERVER_ERROR, SLOW, DROP_CONNECTION }

    public record PaymentCall(String idempotencyKey, JsonNode body) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, String> resultsByKey = new ConcurrentHashMap<>();
    private final List<PaymentCall> payments = new CopyOnWriteArrayList<>();
    private final List<String> refunds = new CopyOnWriteArrayList<>();
    private volatile Mode paymentMode = Mode.APPROVE;
    private volatile Mode refundMode = Mode.APPROVE;
    private volatile long paymentDelayMillis;

    public FakePaymentGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/payments", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void reset() {
        paymentMode = Mode.APPROVE;
        refundMode = Mode.APPROVE;
        paymentDelayMillis = 0;
        payments.clear();
        refunds.clear();
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

    public List<PaymentCall> payments() {
        return List.copyOf(payments);
    }

    public List<String> refunds() {
        return List.copyOf(refunds);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            if (path.equals("/v1/payments")) {
                handlePayment(exchange, requestBody);
            } else if (path.endsWith("/refund")) {
                String paymentId = path.substring("/v1/payments/".length(), path.length() - "/refund".length());
                refunds.add(paymentId);
                respond(exchange, refundMode, "{\"paymentId\":\"%s\",\"status\":\"REFUNDED\"}".formatted(paymentId));
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private void handlePayment(HttpExchange exchange, byte[] requestBody) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        payments.add(new PaymentCall(key, MAPPER.readTree(requestBody)));
        sleep(paymentDelayMillis);
        Mode mode = paymentMode;
        String body = switch (mode) {
            case DECLINE -> resultsByKey.computeIfAbsent(key, k -> result("DECLINED"));
            default -> resultsByKey.computeIfAbsent(key, k -> result("APPROVED"));
        };
        respond(exchange, mode, body);
    }

    private String result(String status) {
        return "{\"paymentId\":\"pay-%d\",\"status\":\"%s\"}".formatted(sequence.incrementAndGet(), status);
    }

    private static void respond(HttpExchange exchange, Mode mode, String successBody) throws IOException {
        switch (mode) {
            case SERVER_ERROR -> exchange.sendResponseHeaders(500, -1);
            case DROP_CONNECTION -> {
                // 응답 헤더 없이 연결을 닫는다(try-with-resources의 close).
            }
            case SLOW -> {
                sleep(3_000);
                send(exchange, successBody);
            }
            default -> send(exchange, successBody);
        }
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
