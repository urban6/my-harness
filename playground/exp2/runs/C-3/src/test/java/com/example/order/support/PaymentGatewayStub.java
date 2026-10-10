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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDK HttpServer 기반 외부 PG 스텁 (feature.md "외부 PG 계약").
 *
 * <ul>
 *   <li>POST /v1/payments : 모드에 따라 APPROVED / DECLINED / 5xx / 지연 응답. 같은 Idempotency-Key는 최초 결과를 돌려준다.</li>
 *   <li>POST /v1/payments/{paymentId}/refund : 모드에 따라 REFUNDED / 5xx / 지연 응답.</li>
 *   <li>호출 횟수는 {@link #paymentCalls()}, {@link #refundCalls()} 로 센다 (멱등 재생 호출 포함 원시 횟수).</li>
 *   <li>{@link #stop()} 으로 서버를 내려 연결 실패를 만들고, {@link #start()} 로 같은 포트에 다시 띄운다.</li>
 * </ul>
 */
public class PaymentGatewayStub {

    public enum Mode {APPROVE, DECLINE, SERVER_ERROR, DELAY, STALL_BODY}

    /** 스텁이 받은 결제 요청 한 건. */
    public record ReceivedPayment(String idempotencyKey, String body) {
    }

    private volatile Mode paymentMode = Mode.APPROVE;
    private volatile Mode refundMode = Mode.APPROVE;
    private volatile long delayMillis = 3000;

    private final AtomicInteger paymentCalls = new AtomicInteger();
    private final AtomicInteger refundCalls = new AtomicInteger();
    private final AtomicInteger paymentIdSeq = new AtomicInteger();
    private final List<ReceivedPayment> receivedPayments = new CopyOnWriteArrayList<>();
    private final List<String> refundedPaymentIds = new CopyOnWriteArrayList<>();
    private final Map<String, String> paymentResultsByKey = new ConcurrentHashMap<>();

    private HttpServer server;
    private ExecutorService executor;
    private int port;

    /** 서버 시작. 최초에는 임의 포트, 이후 start()는 같은 포트를 재사용한다. */
    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.createContext("/v1/payments", this::handle);
            server.start();
            port = server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("PG 스텁 시작 실패", e);
        }
    }

    /** 서버를 내린다 -> 클라이언트는 연결 실패(connection refused)를 본다. */
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        server.stop(0);
        executor.shutdownNow();
        server = null;
        executor = null;
    }

    /** 모드·카운터·기록을 초기화하고, 내려가 있으면 다시 띄운다. 매 테스트 전에 호출한다. */
    public synchronized void reset() {
        start();
        paymentMode = Mode.APPROVE;
        refundMode = Mode.APPROVE;
        delayMillis = 3000;
        paymentCalls.set(0);
        refundCalls.set(0);
        receivedPayments.clear();
        refundedPaymentIds.clear();
        paymentResultsByKey.clear();
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    // ---- 시나리오 전환 ----

    public void approvePayments() {
        paymentMode = Mode.APPROVE;
    }

    public void declinePayments() {
        paymentMode = Mode.DECLINE;
    }

    public void failPaymentsWith5xx() {
        paymentMode = Mode.SERVER_ERROR;
    }

    /** 결제 응답을 millis 만큼 지연 (PG 타임아웃 2초 초과를 만들려면 2500 이상). */
    public void delayPayments(long millis) {
        paymentMode = Mode.DELAY;
        delayMillis = millis;
    }

    /** 결제 응답의 상태줄·헤더는 즉시 보내고 본문만 millis 만큼 지연한다 (헤더 수신 후 본문이 멎는 PG). 승인(APPROVED)으로 응답한다. */
    public void stallPaymentBodies(long millis) {
        paymentMode = Mode.STALL_BODY;
        delayMillis = millis;
    }

    /** 환불 응답의 헤더는 즉시, 본문만 millis 지연. */
    public void stallRefundBodies(long millis) {
        refundMode = Mode.STALL_BODY;
        delayMillis = millis;
    }

    public void approveRefunds() {
        refundMode = Mode.APPROVE;
    }

    public void failRefundsWith5xx() {
        refundMode = Mode.SERVER_ERROR;
    }

    public void delayRefunds(long millis) {
        refundMode = Mode.DELAY;
        delayMillis = millis;
    }

    // ---- 관측 ----

    public int paymentCalls() {
        return paymentCalls.get();
    }

    public int refundCalls() {
        return refundCalls.get();
    }

    public List<ReceivedPayment> receivedPayments() {
        return List.copyOf(receivedPayments);
    }

    public List<String> refundedPaymentIds() {
        return List.copyOf(refundedPaymentIds);
    }

    // ---- 핸들러 ----

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, "{}");
            } else if (path.equals("/v1/payments")) {
                handlePayment(ex);
            } else if (path.matches("/v1/payments/[^/]+/refund")) {
                handleRefund(ex, path.split("/")[3]);
            } else {
                respond(ex, 404, "{}");
            }
        } catch (RuntimeException e) {
            respond(ex, 500, "{}");
        } finally {
            ex.close();
        }
    }

    private void handlePayment(HttpExchange ex) throws IOException {
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        paymentCalls.incrementAndGet();
        receivedPayments.add(new ReceivedPayment(key, body));
        switch (paymentMode) {
            case SERVER_ERROR -> respond(ex, 500, "{\"error\":\"boom\"}");
            case DELAY -> {
                sleep(delayMillis);
                respond(ex, 200, paymentResult(key, "APPROVED"));
            }
            case STALL_BODY -> respondStalled(ex, 200, paymentResult(key, "APPROVED"), delayMillis);
            case DECLINE -> respond(ex, 200, paymentResult(key, "DECLINED"));
            default -> respond(ex, 200, paymentResult(key, "APPROVED"));
        }
    }

    /** 같은 Idempotency-Key면 최초 결과를 그대로 돌려준다. */
    private String paymentResult(String key, String status) {
        return paymentResultsByKey.computeIfAbsent(key == null ? "" : key, k ->
                "{\"paymentId\":\"pay_" + paymentIdSeq.incrementAndGet() + "\",\"status\":\"" + status + "\"}");
    }

    private void handleRefund(HttpExchange ex, String paymentId) throws IOException {
        ex.getRequestBody().readAllBytes();
        refundCalls.incrementAndGet();
        refundedPaymentIds.add(paymentId);
        switch (refundMode) {
            case SERVER_ERROR -> respond(ex, 500, "{\"error\":\"boom\"}");
            case DELAY -> {
                sleep(delayMillis);
                respond(ex, 200, refundBody(paymentId));
            }
            case STALL_BODY -> respondStalled(ex, 200, refundBody(paymentId), delayMillis);
            default -> respond(ex, 200, refundBody(paymentId));
        }
    }

    private static String refundBody(String paymentId) {
        return "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 헤더를 먼저 flush 하고 millis 뒤에 본문을 쓴다. */
    private static void respondStalled(HttpExchange ex, int status, String body, long millis) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, bytes.length);
            OutputStream os = ex.getResponseBody();
            os.flush();
            sleep(millis);
            os.write(bytes);
            os.close();
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊은 경우
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊은 경우
        }
    }
}
