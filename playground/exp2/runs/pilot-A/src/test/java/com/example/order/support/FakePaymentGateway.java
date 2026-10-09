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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 PG. 동작은 cardToken으로 정한다.
 * <ul>
 *   <li>{@code decline...} → DECLINED</li>
 *   <li>{@code error...} → 500</li>
 *   <li>{@code slow...} → 3초 뒤 APPROVED</li>
 *   <li>{@code refund-error...} → 결제는 승인, 환불은 500</li>
 *   <li>{@code refund-slow...} → 결제는 승인, 환불은 3초 지연</li>
 *   <li>그 밖 → APPROVED</li>
 * </ul>
 * 같은 Idempotency-Key의 결제 요청에는 최초 결과를 돌려준다.
 */
public class FakePaymentGateway {

    public record PaymentCall(String idempotencyKey, long orderId, long amount, String cardToken) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<PaymentCall> paymentCalls = new CopyOnWriteArrayList<>();
    private final List<String> refundCalls = new CopyOnWriteArrayList<>();
    private final Map<String, String> resultsByKey = new ConcurrentHashMap<>();
    private final Map<String, String> tokenByPaymentId = new ConcurrentHashMap<>();
    private final Map<String, Long> orderByPaymentId = new ConcurrentHashMap<>();

    public FakePaymentGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/payments", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<PaymentCall> paymentCallsFor(long orderId) {
        return paymentCalls.stream().filter(c -> c.orderId() == orderId).toList();
    }

    /** 이 주문의 결제로 발급된 paymentId에 들어온 환불 요청 수. */
    public long refundCallsForOrder(long orderId) {
        return refundCalls.stream().filter(id -> orderId == orderByPaymentId.getOrDefault(id, -1L)).count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            if (path.equals("/v1/payments")) {
                handlePayment(exchange, mapper.readTree(requestBody));
            } else if (path.matches("/v1/payments/[^/]+/refund")) {
                handleRefund(exchange, path.split("/")[3]);
            } else {
                respond(exchange, 404, "{}");
            }
        }
    }

    private void handlePayment(HttpExchange exchange, JsonNode body) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        String token = body.path("cardToken").asText();
        paymentCalls.add(new PaymentCall(key, body.path("orderId").asLong(), body.path("amount").asLong(), token));
        if (token.startsWith("error")) {
            respond(exchange, 500, "{\"error\":\"boom\"}");
            return;
        }
        if (token.startsWith("slow")) {
            sleep(3000);
        }
        String result = resultsByKey.computeIfAbsent(key, k -> {
            String paymentId = "pay-" + sequence.incrementAndGet();
            tokenByPaymentId.put(paymentId, token);
            orderByPaymentId.put(paymentId, body.path("orderId").asLong());
            String status = token.startsWith("decline") ? "DECLINED" : "APPROVED";
            return "{\"paymentId\":\"" + paymentId + "\",\"status\":\"" + status + "\"}";
        });
        respond(exchange, 200, result);
    }

    private void handleRefund(HttpExchange exchange, String paymentId) throws IOException {
        refundCalls.add(paymentId);
        String token = tokenByPaymentId.getOrDefault(paymentId, "");
        if (token.startsWith("refund-error")) {
            respond(exchange, 500, "{\"error\":\"boom\"}");
            return;
        }
        if (token.startsWith("refund-slow")) {
            sleep(3000);
        }
        respond(exchange, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // 클라이언트가 제한 시간 초과로 연결을 끊은 경우
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
