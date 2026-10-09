package com.example.order;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 테스트용 외부 PG. 결제 결과는 cardToken 접두어로 정한다.
 * <ul>
 *   <li>{@code decline…} → DECLINED</li>
 *   <li>{@code error…} → 500</li>
 *   <li>{@code timeout…} → 3초 뒤 응답 (클라이언트 타임아웃 2초 초과)</li>
 *   <li>{@code slow…} → 300ms 뒤 APPROVED</li>
 *   <li>그 밖 → APPROVED</li>
 * </ul>
 * 실제 PG처럼 같은 Idempotency-Key의 결제 요청에는 다시 결제하지 않고 최초 결과를 돌려준다.
 */
public final class FakePaymentGateway {

    public enum RefundMode { OK, ERROR, TIMEOUT }

    public record PaymentCall(String idempotencyKey, long orderId, long amount, String cardToken) {
    }

    private static final Pattern REFUND_PATH = Pattern.compile("/v1/payments/([^/]+)/refund");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final Map<String, String> resultsByKey = new ConcurrentHashMap<>();
    private final List<PaymentCall> paymentCalls = new CopyOnWriteArrayList<>();
    private final List<PaymentCall> charges = new CopyOnWriteArrayList<>();
    private final List<String> refunds = new CopyOnWriteArrayList<>();
    private final Map<Long, String> paymentIdByOrder = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile RefundMode refundMode = RefundMode.OK;

    private FakePaymentGateway(HttpServer server) {
        this.server = server;
    }

    public static FakePaymentGateway start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakePaymentGateway gateway = new FakePaymentGateway(server);
            server.createContext("/v1/payments", gateway::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return gateway;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setRefundMode(RefundMode refundMode) {
        this.refundMode = refundMode;
    }

    /** 이 주문에 대해 PG가 받은 모든 결제 요청. */
    public List<PaymentCall> paymentCallsFor(long orderId) {
        return paymentCalls.stream().filter(c -> c.orderId() == orderId).toList();
    }

    /** 이 주문에 대해 실제로 처리된(멱등 재생이 아닌) 결제. */
    public List<PaymentCall> chargesFor(long orderId) {
        return charges.stream().filter(c -> c.orderId() == orderId).toList();
    }

    /** 이 주문에 대해 PG가 발급한 paymentId. */
    public String paymentIdFor(long orderId) {
        return paymentIdByOrder.get(orderId);
    }

    public List<String> refunds() {
        return List.copyOf(refunds);
    }

    private void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            Matcher refund = REFUND_PATH.matcher(path);
            if ("POST".equals(exchange.getRequestMethod()) && path.equals("/v1/payments")) {
                handlePayment(exchange);
            } else if ("POST".equals(exchange.getRequestMethod()) && refund.matches()) {
                handleRefund(exchange, refund.group(1));
            } else {
                respond(exchange, 404, "{}");
            }
        } catch (IOException | InterruptedException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊은 경우
        } finally {
            exchange.close();
        }
    }

    private void handlePayment(HttpExchange exchange) throws IOException, InterruptedException {
        JsonNode body = mapper.readTree(exchange.getRequestBody());
        PaymentCall call = new PaymentCall(
                exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                body.path("orderId").asLong(),
                body.path("amount").asLong(),
                body.path("cardToken").asText());
        paymentCalls.add(call);

        String token = call.cardToken();
        if (token.startsWith("error")) {
            respond(exchange, 500, "{\"message\":\"internal error\"}");
            return;
        }
        if (token.startsWith("timeout")) {
            Thread.sleep(3_000);
            respond(exchange, 200, result("pay_" + sequence.incrementAndGet(), "APPROVED"));
            return;
        }
        if (token.startsWith("slow")) {
            Thread.sleep(300);
        }
        String result = resultsByKey.computeIfAbsent(call.idempotencyKey(), key -> {
            charges.add(call);
            String paymentId = "pay_" + sequence.incrementAndGet();
            paymentIdByOrder.put(call.orderId(), paymentId);
            return result(paymentId, token.startsWith("decline") ? "DECLINED" : "APPROVED");
        });
        respond(exchange, 200, result);
    }

    private void handleRefund(HttpExchange exchange, String paymentId) throws IOException, InterruptedException {
        switch (refundMode) {
            case ERROR -> respond(exchange, 503, "{\"message\":\"unavailable\"}");
            case TIMEOUT -> {
                Thread.sleep(3_000);
                respond(exchange, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
            }
            case OK -> {
                refunds.add(paymentId);
                respond(exchange, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
            }
        }
    }

    private static String result(String paymentId, String status) {
        return "{\"paymentId\":\"" + paymentId + "\",\"status\":\"" + status + "\"}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
