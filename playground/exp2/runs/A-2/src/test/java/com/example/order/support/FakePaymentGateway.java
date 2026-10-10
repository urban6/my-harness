package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 테스트용 외부 PG. 같은 Idempotency-Key의 결제 요청에는 최초 결과를 돌려준다. */
public class FakePaymentGateway {

    public enum Behavior { APPROVE, DECLINE, SERVER_ERROR, DROP_CONNECTION }

    public record PaymentRequest(String idempotencyKey, JsonNode body) {
    }

    private static final Pattern REFUND = Pattern.compile("/v1/payments/([^/]+)/refund");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private volatile Behavior paymentBehavior = Behavior.APPROVE;
    private volatile long paymentDelayMs;
    private volatile Behavior refundBehavior = Behavior.APPROVE;
    private volatile long refundDelayMs;
    private final List<PaymentRequest> paymentRequests = new CopyOnWriteArrayList<>();
    private final List<String> refundRequests = new CopyOnWriteArrayList<>();
    private final Map<String, String> resultsByKey = new ConcurrentHashMap<>();

    public FakePaymentGateway() {
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
        paymentBehavior = Behavior.APPROVE;
        paymentDelayMs = 0;
        refundBehavior = Behavior.APPROVE;
        refundDelayMs = 0;
        paymentRequests.clear();
        refundRequests.clear();
        resultsByKey.clear();
    }

    public void paymentBehavior(Behavior behavior) {
        this.paymentBehavior = behavior;
    }

    public void paymentDelayMs(long delayMs) {
        this.paymentDelayMs = delayMs;
    }

    public void refundBehavior(Behavior behavior) {
        this.refundBehavior = behavior;
    }

    public void refundDelayMs(long delayMs) {
        this.refundDelayMs = delayMs;
    }

    public List<PaymentRequest> paymentRequests() {
        return paymentRequests;
    }

    public List<String> refundRequests() {
        return refundRequests;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("POST".equals(exchange.getRequestMethod()) && path.equals("/v1/payments")) {
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                paymentRequests.add(new PaymentRequest(key, JSON.readTree(body)));
                sleep(paymentDelayMs);
                respond(exchange, paymentBehavior, () -> resultsByKey.computeIfAbsent(String.valueOf(key), k ->
                        json(Map.of("paymentId", "pay_" + UUID.randomUUID(),
                                "status", paymentBehavior == Behavior.DECLINE ? "DECLINED" : "APPROVED"))));
                return;
            }
            Matcher refund = REFUND.matcher(path);
            if ("POST".equals(exchange.getRequestMethod()) && refund.matches()) {
                refundRequests.add(refund.group(1));
                sleep(refundDelayMs);
                respond(exchange, refundBehavior,
                        () -> json(Map.of("paymentId", refund.group(1), "status", "REFUNDED")));
                return;
            }
            send(exchange, 404, "{}");
        } catch (IOException e) {
            // 클라이언트가 제한 시간 초과로 먼저 끊은 경우
        }
    }

    private void respond(HttpExchange exchange, Behavior behavior, java.util.function.Supplier<String> success)
            throws IOException {
        switch (behavior) {
            case SERVER_ERROR -> send(exchange, 500, "{\"error\":\"boom\"}");
            case DROP_CONNECTION -> {
                // 응답 헤더 없이 exchange를 닫으면 연결이 끊긴다
            }
            default -> send(exchange, 200, success.get());
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
