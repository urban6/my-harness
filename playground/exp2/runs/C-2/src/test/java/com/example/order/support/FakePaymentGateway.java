package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 테스트용 가짜 PG (JDK HttpServer, 127.0.0.1:임의 포트).
 *
 * <pre>
 * POST /v1/payments                  -> paymentMode 에 따라 APPROVED / DECLINED / 500 / (지연 후 APPROVED)
 * POST /v1/payments/{id}/refund      -> refundMode 에 따라 REFUNDED / 500 / (지연 후 REFUNDED)
 * </pre>
 * 같은 Idempotency-Key의 결제 요청에는 최초 결과(APPROVED/DECLINED + paymentId)를 돌려준다. 호출은 재생이어도 모두 기록한다.
 * 500은 최초 결과로 기억하지 않는다. 상태는 {@link #reset()}으로 초기화한다 (베이스 클래스가 매 테스트 전에 호출).
 */
public final class FakePaymentGateway {

    public enum PaymentMode { APPROVE, DECLINE, HTTP_500, DELAY }

    public enum RefundMode { REFUND_OK, REFUND_500, DELAY }

    /** PG가 받은 결제 요청. idempotencyKey는 헤더 원문(없으면 null). */
    public record PaymentCall(String idempotencyKey, String rawBody, long orderId, long amount, String cardToken) {
    }

    public record RefundCall(String paymentId) {
    }

    private static final Pattern REFUND_PATH = Pattern.compile("^/v1/payments/([^/]+)/refund$");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final List<PaymentCall> paymentCalls = new CopyOnWriteArrayList<>();
    private final List<RefundCall> refundCalls = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, String[]> firstResultByKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private volatile PaymentMode paymentMode = PaymentMode.APPROVE;
    private volatile RefundMode refundMode = RefundMode.REFUND_OK;
    private volatile long delayMillis = 0;

    public FakePaymentGateway() {
        this(0);
    }

    /** 지정한 포트로 뜬다 (0이면 임의 포트). 이미 닫힌 포트를 나중에 다시 여는 시나리오에 쓴다. */
    public FakePaymentGateway(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-pg");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/v1/payments", this::handle);
        server.start();
    }

    // ---- 설정 ----

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setPaymentMode(PaymentMode mode) {
        this.paymentMode = mode;
    }

    public void setRefundMode(RefundMode mode) {
        this.refundMode = mode;
    }

    /** DELAY 모드(결제/환불)에서 응답 전에 기다리는 시간. */
    public void setDelayMillis(long millis) {
        this.delayMillis = millis;
    }

    public void approve() { setPaymentMode(PaymentMode.APPROVE); }

    public void decline() { setPaymentMode(PaymentMode.DECLINE); }

    public void http500() { setPaymentMode(PaymentMode.HTTP_500); }

    /** 결제 응답을 millis 만큼 늦춘다 (2초 타임아웃 검증에는 3000 등). */
    public void delayPayment(long millis) {
        setDelayMillis(millis);
        setPaymentMode(PaymentMode.DELAY);
    }

    public void refundOk() { setRefundMode(RefundMode.REFUND_OK); }

    public void refund500() { setRefundMode(RefundMode.REFUND_500); }

    public void delayRefund(long millis) {
        setDelayMillis(millis);
        setRefundMode(RefundMode.DELAY);
    }

    public void reset() {
        paymentMode = PaymentMode.APPROVE;
        refundMode = RefundMode.REFUND_OK;
        delayMillis = 0;
        paymentCalls.clear();
        refundCalls.clear();
        firstResultByKey.clear();
    }

    public void stop() {
        server.stop(0);
    }

    // ---- 기록 ----

    public List<PaymentCall> paymentCalls() {
        return List.copyOf(paymentCalls);
    }

    public int paymentCallCount() {
        return paymentCalls.size();
    }

    public List<PaymentCall> paymentCallsForOrder(long orderId) {
        return paymentCalls.stream().filter(c -> c.orderId() == orderId).toList();
    }

    /** 해당 Idempotency-Key의 최초 결제 결과로 발급한 paymentId (없으면 null). */
    public String paymentIdForKey(String idempotencyKey) {
        String[] result = firstResultByKey.get(idempotencyKey);
        return result == null ? null : result[0];
    }

    public List<RefundCall> refundCalls() {
        return List.copyOf(refundCalls);
    }

    // ---- 핸들러 ----

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            byte[] requestBody = ex.getRequestBody().readAllBytes();
            if (!"POST".equals(ex.getRequestMethod())) {
                send(ex, 405, "{}");
            } else if ("/v1/payments".equals(path)) {
                handlePayment(ex, new String(requestBody, StandardCharsets.UTF_8));
            } else {
                Matcher m = REFUND_PATH.matcher(path);
                if (m.matches()) {
                    handleRefund(ex, m.group(1));
                } else {
                    send(ex, 404, "{}");
                }
            }
        } catch (IOException e) {
            // 클라이언트가 타임아웃으로 먼저 끊은 경우
        } finally {
            ex.close();
        }
    }

    private void handlePayment(HttpExchange ex, String rawBody) throws IOException {
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        JsonNode json = mapper.readTree(rawBody);
        paymentCalls.add(new PaymentCall(key, rawBody, json.path("orderId").asLong(), json.path("amount").asLong(),
                json.path("cardToken").asText(null)));

        PaymentMode mode = paymentMode;
        if (mode == PaymentMode.HTTP_500) {
            send(ex, 500, "{\"error\":\"boom\"}");
            return;
        }
        if (mode == PaymentMode.DELAY) {
            sleep(delayMillis);
        }
        String wanted = mode == PaymentMode.DECLINE ? "DECLINED" : "APPROVED";
        String[] result = key == null
                ? newResult(wanted)
                : firstResultByKey.computeIfAbsent(key, k -> newResult(wanted));
        send(ex, 200, "{\"paymentId\":\"" + result[0] + "\",\"status\":\"" + result[1] + "\"}");
    }

    private void handleRefund(HttpExchange ex, String paymentId) throws IOException {
        refundCalls.add(new RefundCall(paymentId));
        RefundMode mode = refundMode;
        if (mode == RefundMode.REFUND_500) {
            send(ex, 500, "{\"error\":\"boom\"}");
            return;
        }
        if (mode == RefundMode.DELAY) {
            sleep(delayMillis);
        }
        send(ex, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
    }

    private String[] newResult(String status) {
        return new String[] {"pay_" + sequence.incrementAndGet(), status};
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
