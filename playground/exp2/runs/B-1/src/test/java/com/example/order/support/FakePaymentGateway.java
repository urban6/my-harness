package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * 외부 PG 계약을 흉내 내는 테스트용 HTTP 서버. 같은 Idempotency-Key 의 결제 요청에는 최초 결과를 돌려준다.
 */
public final class FakePaymentGateway {

    public enum Mode {
        APPROVE, DECLINE, SERVER_ERROR, SLOW, DROP_CONNECTION
    }

    public record PaymentCall(String idempotencyKey, JsonNode body) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration SLOW_DELAY = Duration.ofSeconds(3);

    private final HttpServer server;
    private final List<PaymentCall> paymentCalls = new CopyOnWriteArrayList<>();
    private final List<String> refundCalls = new CopyOnWriteArrayList<>();
    private final Map<String, String> resultByKey = new ConcurrentHashMap<>();
    private volatile Mode paymentMode = Mode.APPROVE;
    private volatile Mode refundMode = Mode.APPROVE;
    private volatile Duration latency = Duration.ZERO;

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

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        paymentCalls.clear();
        refundCalls.clear();
        resultByKey.clear();
        paymentMode = Mode.APPROVE;
        refundMode = Mode.APPROVE;
        latency = Duration.ZERO;
    }

    public void paymentMode(Mode mode) {
        this.paymentMode = mode;
    }

    public void refundMode(Mode mode) {
        this.refundMode = mode;
    }

    /** 정상 응답 전 지연(동시성 테스트에서 경쟁 구간을 넓히는 용도). */
    public void latency(Duration latency) {
        this.latency = latency;
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
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{}");
            } else if (path.equals("/v1/payments")) {
                handlePayment(exchange);
            } else if (path.matches("/v1/payments/[^/]+/refund")) {
                handleRefund(exchange, path.split("/")[3]);
            } else {
                respond(exchange, 404, "{}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handlePayment(HttpExchange exchange) throws IOException, InterruptedException {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        JsonNode body = JSON.readTree(exchange.getRequestBody());
        paymentCalls.add(new PaymentCall(key, body));
        Mode mode = paymentMode;
        if (failed(exchange, mode)) {
            return;
        }
        Thread.sleep(latency.toMillis());
        String status = mode == Mode.DECLINE ? "DECLINED" : "APPROVED";
        String result = resultByKey.computeIfAbsent(key, k ->
                "{\"paymentId\":\"pay_" + UUID.randomUUID() + "\",\"status\":\"" + status + "\"}");
        respond(exchange, 200, result);
    }

    private void handleRefund(HttpExchange exchange, String paymentId) throws IOException, InterruptedException {
        exchange.getRequestBody().readAllBytes();
        refundCalls.add(paymentId);
        if (failed(exchange, refundMode)) {
            return;
        }
        respond(exchange, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
    }

    /** 장애 모드면 장애 응답을 보내고 true. */
    private static boolean failed(HttpExchange exchange, Mode mode) throws IOException, InterruptedException {
        switch (mode) {
            case SERVER_ERROR -> {
                respond(exchange, 500, "{\"error\":\"internal\"}");
                return true;
            }
            case SLOW -> {
                Thread.sleep(SLOW_DELAY.toMillis());
                respond(exchange, 200, "{\"paymentId\":\"late\",\"status\":\"APPROVED\"}");
                return true;
            }
            case DROP_CONNECTION -> {
                return true; // 응답 없이 연결을 닫는다
            }
            default -> {
                return false;
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊은 경우
        }
    }
}
