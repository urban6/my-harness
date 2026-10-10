package com.example.order.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-process fake of the external PG (JDK HttpServer, no extra dependency). Honors Idempotency-Key on charges:
 * the same key returns the same payment instead of creating a new one.
 */
public class PaymentGatewayStub {

    /**
     * OK/DECLINE are the happy results. SERVER_ERROR=5xx, CLIENT_ERROR=4xx, MALFORMED=200 with a non-JSON body,
     * UNKNOWN_STATUS=200 with a status the service does not know, DROP=close the socket without any response.
     */
    public enum Behavior { OK, DECLINE, SERVER_ERROR, CLIENT_ERROR, MALFORMED, UNKNOWN_STATUS, DROP }

    public record RecordedRequest(String method, String path, Map<String, String> headers, String body) {
    }

    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> chargeResponsesByKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile Behavior chargeBehavior = Behavior.OK;
    private volatile Behavior refundBehavior = Behavior.OK;
    private volatile long delayMillis = 0;
    private HttpServer server;
    private int port;

    public synchronized void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            port = server.getAddress().getPort();
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "pg-stub");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Makes the PG unreachable (connection refused) on the same port the application was configured with. */
    public synchronized void refuseConnections() {
        stop();
    }

    public synchronized void acceptConnections() {
        if (server == null) {
            start();
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public void reset() {
        acceptConnections();
        requests.clear();
        chargeResponsesByKey.clear();
        chargeBehavior = Behavior.OK;
        refundBehavior = Behavior.OK;
        delayMillis = 0;
    }

    public void chargeBehavior(Behavior behavior) {
        this.chargeBehavior = behavior;
    }

    public void refundBehavior(Behavior behavior) {
        this.refundBehavior = behavior;
    }

    public void delayMillis(long millis) {
        this.delayMillis = millis;
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public List<RecordedRequest> charges() {
        return requests.stream().filter(r -> r.path().equals("/v1/payments")).collect(Collectors.toList());
    }

    public List<RecordedRequest> refunds() {
        return requests.stream().filter(r -> r.path().endsWith("/refund")).collect(Collectors.toList());
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = ex.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toLowerCase(Locale.ROOT), e -> e.getValue().get(0), (a, b) -> a));
        String path = ex.getRequestURI().getPath();
        requests.add(new RecordedRequest(ex.getRequestMethod(), path, headers, body));
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if ((path.equals("/v1/payments") && chargeBehavior == Behavior.DROP)
                || (path.endsWith("/refund") && refundBehavior == Behavior.DROP)) {
            ex.close(); // abrupt close without a response
            return;
        }
        if (path.equals("/v1/payments") && "POST".equals(ex.getRequestMethod())) {
            respondCharge(ex, headers.getOrDefault("idempotency-key", ""));
        } else if (path.matches("/v1/payments/[^/]+/refund") && "POST".equals(ex.getRequestMethod())) {
            String paymentId = path.split("/")[3];
            respondRefund(ex, paymentId);
        } else {
            send(ex, 404, "{}");
        }
    }

    private void respondCharge(HttpExchange ex, String key) throws IOException {
        switch (chargeBehavior) {
            case SERVER_ERROR -> send(ex, 500, "{\"error\":\"boom\"}");
            case CLIENT_ERROR -> send(ex, 400, "{\"error\":\"bad request\"}");
            case MALFORMED -> send(ex, 200, "not-json");
            case UNKNOWN_STATUS -> send(ex, 200, "{\"paymentId\":\"pg-x\",\"status\":\"WEIRD\"}");
            default -> {
                String json = chargeResponsesByKey.computeIfAbsent(key, k -> {
                    String status = chargeBehavior == Behavior.DECLINE ? "DECLINED" : "APPROVED";
                    return "{\"paymentId\":\"pg-" + sequence.incrementAndGet() + "\",\"status\":\"" + status + "\"}";
                });
                send(ex, 200, json);
            }
        }
    }

    private void respondRefund(HttpExchange ex, String paymentId) throws IOException {
        if (refundBehavior == Behavior.SERVER_ERROR) {
            send(ex, 503, "{\"error\":\"unavailable\"}");
        } else if (refundBehavior == Behavior.CLIENT_ERROR) {
            send(ex, 404, "{\"error\":\"unknown payment\"}");
        } else if (refundBehavior == Behavior.MALFORMED) {
            send(ex, 200, "not-json");
        } else if (refundBehavior == Behavior.UNKNOWN_STATUS) {
            send(ex, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"WEIRD\"}");
        } else {
            send(ex, 200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
        }
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
