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

/**
 * 테스트용 외부 PG. 동작은 cardToken 으로 정한다.
 * <ul>
 *   <li>{@code tok_decline} → DECLINED</li>
 *   <li>{@code tok_500} → HTTP 500</li>
 *   <li>{@code tok_slow} → 3초 후 APPROVED (시간 초과 유발)</li>
 *   <li>{@code tok_slow_ok} → 0.8초 후 APPROVED</li>
 *   <li>{@code tok_drop} → 응답 없이 연결 종료</li>
 *   <li>{@code tok_refund_500} / {@code tok_refund_slow} → 승인, 이후 환불이 500 / 3초 지연</li>
 *   <li>그 밖 → APPROVED</li>
 * </ul>
 * 같은 Idempotency-Key 의 결제 요청에는 최초 결과를 돌려준다.
 */
public final class FakePaymentGateway {

    public record PaymentCall(String idempotencyKey, long orderId, long amount, String cardToken) {
    }

    private static final FakePaymentGateway INSTANCE = new FakePaymentGateway();

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final List<PaymentCall> paymentCalls = new CopyOnWriteArrayList<>();
    private final List<String> refundCalls = new CopyOnWriteArrayList<>();
    private final Map<String, String> resultsByKey = new ConcurrentHashMap<>();
    private final Map<String, String> behaviorByPaymentId = new ConcurrentHashMap<>();

    private FakePaymentGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/payments", this::handle);
        server.start();
    }

    public static FakePaymentGateway get() {
        return INSTANCE;
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public List<PaymentCall> paymentCallsFor(long orderId) {
        return paymentCalls.stream().filter(c -> c.orderId() == orderId).toList();
    }

    public List<String> refundCalls() {
        return List.copyOf(refundCalls);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/v1/payments")) {
                handlePayment(exchange);
            } else if (path.endsWith("/refund")) {
                handleRefund(exchange, path.substring("/v1/payments/".length(), path.length() - "/refund".length()));
            } else {
                respond(exchange, 404, "{}");
            }
        }
    }

    private void handlePayment(HttpExchange exchange) throws IOException {
        JsonNode body = mapper.readTree(exchange.getRequestBody());
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        String token = body.path("cardToken").asText();
        paymentCalls.add(new PaymentCall(key, body.path("orderId").asLong(), body.path("amount").asLong(), token));

        switch (token) {
            case "tok_500" -> {
                respond(exchange, 500, "{\"error\":\"boom\"}");
                return;
            }
            case "tok_drop" -> {
                return; // 응답 헤더 없이 연결을 닫는다
            }
            case "tok_slow" -> sleep(3_000);
            case "tok_slow_ok" -> sleep(800);
            default -> {
            }
        }
        String result = resultsByKey.computeIfAbsent(key, k -> {
            String paymentId = "pay_" + UUID.randomUUID();
            behaviorByPaymentId.put(paymentId, token);
            String status = token.equals("tok_decline") ? "DECLINED" : "APPROVED";
            return "{\"paymentId\":\"" + paymentId + "\",\"status\":\"" + status + "\"}";
        });
        respond(exchange, 200, result);
    }

    private void handleRefund(HttpExchange exchange, String paymentId) throws IOException {
        refundCalls.add(paymentId);
        String behavior = behaviorByPaymentId.getOrDefault(paymentId, "");
        if (behavior.equals("tok_refund_500")) {
            respond(exchange, 503, "{\"error\":\"unavailable\"}");
            return;
        }
        if (behavior.equals("tok_refund_slow")) {
            sleep(3_000);
        }
        respond(exchange, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
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
