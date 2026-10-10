package com.example.order.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDK HttpServer 기반 가짜 PG. 기본 동작은 PG 계약을 따른다:
 * 같은 Idempotency-Key 의 결제는 최초 결과를 돌려주고, cardToken 이 "decline" 으로 시작하면 DECLINED,
 * 그 외 APPROVED, 환불은 REFUNDED. {@link #respondWith(Handler)} 로 응답(지연·5xx 포함)을 바꿀 수 있다.
 */
public class FakePaymentGateway {

    public record Recorded(String method, String path, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    public record Response(int status, String body, long delayMillis) {
        public static Response json(String body) {
            return new Response(200, body, 0);
        }

        public static Response status(int status) {
            return new Response(status, "{}", 0);
        }

        public Response delayed(long millis) {
            return new Response(status, body, millis);
        }

        /** 응답 없이 연결을 끊는다(연결 실패 시뮬레이션). */
        public static Response drop() {
            return new Response(-1, "", 0);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Recorded request);
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> paymentsByKey = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile Handler handler = this::defaultHandler;

    public FakePaymentGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::serve);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respondWith(Handler handler) {
        this.handler = handler;
    }

    /** 기록·멱등 상태·핸들러를 초기화한다 (테스트 간 격리용). */
    public void reset() {
        requests.clear();
        paymentsByKey.clear();
        handler = this::defaultHandler;
    }

    public List<Recorded> requests() {
        return new ArrayList<>(requests);
    }

    public List<Recorded> requestsTo(String pathPrefix) {
        return requests.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    public void stop() {
        server.stop(0);
    }

    private Response defaultHandler(Recorded r) {
        if (r.path().equals("/v1/payments")) {
            String key = r.header("Idempotency-Key");
            String json = paymentsByKey.computeIfAbsent(String.valueOf(key), k -> {
                boolean decline = r.body().contains("\"cardToken\":\"decline");
                return decline
                        ? "{\"paymentId\":\"pay-" + sequence.incrementAndGet() + "\",\"status\":\"DECLINED\"}"
                        : "{\"paymentId\":\"pay-" + sequence.incrementAndGet() + "\",\"status\":\"APPROVED\"}";
            });
            return Response.json(json);
        }
        if (r.path().matches("/v1/payments/[^/]+/refund")) {
            String paymentId = r.path().split("/")[3];
            return Response.json("{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
        }
        return Response.status(404);
    }

    private void serve(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
        Recorded recorded = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers,
                new String(bodyBytes, StandardCharsets.UTF_8));
        requests.add(recorded);
        Response response;
        try {
            response = handler.handle(recorded);
            if (response.delayMillis() > 0) {
                Thread.sleep(response.delayMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.close();
            return;
        }
        if (response.status() < 0) {
            exchange.close();
            return;
        }
        byte[] out = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(response.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊은 경우
        } finally {
            exchange.close();
        }
    }
}
