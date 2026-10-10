package com.example.order.support;

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
 * 테스트용 가짜 PG (JDK HttpServer). PG 계약: 같은 Idempotency-Key 의 결제 요청에는 최초 결과를 돌려준다.
 * 동작은 {@link Mode} 로 바꾼다. test-writer 가 확장 대상으로 삼는다.
 */
public class FakePaymentGateway {

    public enum Mode { APPROVE, DECLINE, ERROR_500, SLOW }

    public record Recorded(String method, String path, String idempotencyKey, String body) {
    }

    private HttpServer server;
    private volatile Mode mode = Mode.APPROVE;
    private volatile boolean refundFails = false;
    private volatile long slowMillis = 3500;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> firstResultByKey = new ConcurrentHashMap<>();
    private final AtomicInteger paymentSeq = new AtomicInteger();

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/payments", this::handle);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        mode = Mode.APPROVE;
        refundFails = false;
        requests.clear();
        firstResultByKey.clear();
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public void setRefundFails(boolean refundFails) {
        this.refundFails = refundFails;
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public long chargeCount() {
        return requests.stream().filter(r -> r.path().equals("/v1/payments")).count();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        String idemKey = ex.getRequestHeaders().getFirst("Idempotency-Key");
        requests.add(new Recorded(ex.getRequestMethod(), path, idemKey, body));
        try {
            if (path.equals("/v1/payments")) {
                charge(ex, idemKey);
            } else if (path.endsWith("/refund")) {
                String paymentId = path.substring("/v1/payments/".length(), path.length() - "/refund".length());
                if (refundFails) {
                    respond(ex, 500, "{}");
                } else {
                    respond(ex, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
                }
            } else {
                respond(ex, 404, "{}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void charge(HttpExchange ex, String idemKey) throws IOException, InterruptedException {
        Mode m = mode;
        if (m == Mode.ERROR_500) {
            respond(ex, 500, "{}");
            return;
        }
        if (m == Mode.SLOW) {
            Thread.sleep(slowMillis);
        }
        String result = firstResultByKey.computeIfAbsent(idemKey == null ? "" : idemKey, k -> {
            String status = m == Mode.DECLINE ? "DECLINED" : "APPROVED";
            return "{\"paymentId\":\"pay_" + paymentSeq.incrementAndGet() + "\",\"status\":\"" + status + "\"}";
        });
        try {
            respond(ex, 200, result);
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊은 경우
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
