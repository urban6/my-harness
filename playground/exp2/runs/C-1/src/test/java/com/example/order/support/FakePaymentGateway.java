package com.example.order.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 테스트용 가짜 PG 서버(JDK HttpServer, 추가 의존성 없음).
 *
 * <ul>
 *   <li>POST /v1/payments : 헤더 Idempotency-Key, 본문 {orderId, amount, cardToken}</li>
 *   <li>POST /v1/payments/{id}/refund</li>
 * </ul>
 * 응답 모드는 테스트가 {@link #approve()}, {@link #decline()}, {@link #fail5xx()}, {@link #unreachable()} 로 바꾸고,
 * {@link #delay(long)} 로 응답 지연(ms)을 추가한다(모드와 독립). 지연은 모드 처리 전에 적용된다.
 * 같은 Idempotency-Key 결제 요청은 최초 결과(승인/거절)를 그대로 돌려준다(PG 계약).
 * 5xx 응답은 "결과"가 아니므로 저장하지 않는다. 모든 필드는 스레드 안전하다.
 */
public class FakePaymentGateway {

    public enum Mode { APPROVED, DECLINED, ERROR_5XX }

    /** 수신한 요청 기록. headers 키는 소문자. */
    public record RecordedRequest(String method, String path, Map<String, String> headers, String body) {
        public String idempotencyKey() {
            return headers.get("idempotency-key");
        }
    }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.APPROVED);
    private final AtomicLong delayMillis = new AtomicLong(0);
    private final AtomicInteger payCalls = new AtomicInteger();
    private final AtomicInteger refundCalls = new AtomicInteger();
    private final AtomicLong paymentSeq = new AtomicLong();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    /** Idempotency-Key -> 최초 결제 응답 본문 */
    private final Map<String, String> paymentsByKey = new ConcurrentHashMap<>();

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int port;

    public synchronized void start() {
        try {
            bind(0);
        } catch (IOException e) {
            throw new IllegalStateException("가짜 PG 서버를 시작하지 못했습니다.", e);
        }
    }

    public synchronized void stop() {
        shutdown();
    }

    public String url() {
        return "http://localhost:" + port;
    }

    public int port() {
        return port;
    }

    // ---- 모드 전환 ----

    public FakePaymentGateway approve() {
        return mode(Mode.APPROVED);
    }

    public FakePaymentGateway decline() {
        return mode(Mode.DECLINED);
    }

    public FakePaymentGateway fail5xx() {
        return mode(Mode.ERROR_5XX);
    }

    private FakePaymentGateway mode(Mode m) {
        restoreIfStopped();
        mode.set(m);
        return this;
    }

    /** 응답 전 지연(ms). 0이면 지연 없음. */
    public FakePaymentGateway delay(long millis) {
        delayMillis.set(millis);
        return this;
    }

    /** 연결 불가: 리스너를 닫아 연결 거부(Connection refused)를 유발한다. approve()/decline()/fail5xx() 로 복구. */
    public synchronized FakePaymentGateway unreachable() {
        shutdown();
        return this;
    }

    private synchronized void restoreIfStopped() {
        if (server == null) {
            try {
                bind(port);
            } catch (IOException e) {
                throw new IllegalStateException("가짜 PG 서버를 같은 포트로 재시작하지 못했습니다.", e);
            }
        }
    }

    /** 모드 APPROVED, 지연 0, 기록 전부 초기화. 중단된 서버는 같은 포트로 재시작한다. */
    public synchronized void reset() {
        restoreIfStopped();
        mode.set(Mode.APPROVED);
        delayMillis.set(0);
        payCalls.set(0);
        refundCalls.set(0);
        paymentSeq.set(0);
        requests.clear();
        paymentsByKey.clear();
    }

    // ---- 기록 조회 ----

    public int payCallCount() {
        return payCalls.get();
    }

    public int refundCallCount() {
        return refundCalls.get();
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public RecordedRequest lastPayRequest() {
        return lastMatching("/v1/payments");
    }

    public RecordedRequest lastRefundRequest() {
        RecordedRequest last = null;
        for (RecordedRequest r : requests) {
            if (r.path().endsWith("/refund")) {
                last = r;
            }
        }
        return last;
    }

    private RecordedRequest lastMatching(String exactPath) {
        RecordedRequest last = null;
        for (RecordedRequest r : requests) {
            if (r.path().equals(exactPath)) {
                last = r;
            }
        }
        return last;
    }

    /** 서로 다른 Idempotency-Key 로 실제 처리된 결제 건수. */
    public int distinctPaymentCount() {
        return paymentsByKey.size();
    }

    // ---- 내부 ----

    private void bind(int bindPort) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", bindPort), 128);
        ExecutorService ex = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-pg");
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(ex);
        s.createContext("/v1/payments", new PaymentsHandler());
        s.start();
        this.server = s;
        this.executor = ex;
        this.port = s.getAddress().getPort();
    }

    private void shutdown() {
        HttpServer s = this.server;
        ExecutorService ex = this.executor;
        this.server = null;
        this.executor = null;
        if (s != null) {
            s.stop(0);
        }
        if (ex != null) {
            ex.shutdownNow();
        }
    }

    private class PaymentsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> headers = new ConcurrentHashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> {
                    if (!v.isEmpty()) {
                        headers.put(k.toLowerCase(), v.get(0));
                    }
                });
                boolean refund = path.endsWith("/refund");
                if (refund) {
                    refundCalls.incrementAndGet();
                } else {
                    payCalls.incrementAndGet();
                }
                requests.add(new RecordedRequest(exchange.getRequestMethod(), path, headers, body));

                long delay = delayMillis.get();
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (!"POST".equals(exchange.getRequestMethod())) {
                    respond(exchange, 405, "{}");
                    return;
                }
                Mode m = mode.get();
                if (m == Mode.ERROR_5XX) {
                    respond(exchange, 500, "{\"error\":\"pg down\"}");
                    return;
                }
                if (refund) {
                    String paymentId = path.substring("/v1/payments/".length(), path.length() - "/refund".length());
                    respond(exchange, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
                    return;
                }
                String key = headers.getOrDefault("idempotency-key", "");
                String result = paymentsByKey.computeIfAbsent(key, k -> {
                    String status = m == Mode.DECLINED ? "DECLINED" : "APPROVED";
                    return "{\"paymentId\":\"pay_" + paymentSeq.incrementAndGet() + "\",\"status\":\"" + status + "\"}";
                });
                respond(exchange, 200, result);
            }
        }

        private void respond(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
