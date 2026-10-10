package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Programmable external PG (JDK HttpServer, same approach as OrderPaymentSmokeTest). Records every call.
 * Default behaviour: approve (cardToken "tok_decline" -> DECLINED), refund -> REFUNDED.
 */
public final class PgStub {

    /** Recorded request. */
    public record Call(String method, String path, String idempotencyKey, JsonNode body) {
        public boolean isRefund() {
            return path.endsWith("/refund");
        }
    }

    /** Scripted reply. dropConnection=true closes the socket without any response bytes. */
    public record Reply(int status, String body, long delayMillis, boolean dropConnection) {
        public static Reply json(int status, String body) {
            return new Reply(status, body, 0, false);
        }

        public static Reply approved(String paymentId) {
            return json(200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"APPROVED\"}");
        }

        public static Reply declined(String paymentId) {
            return json(200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"DECLINED\"}");
        }

        public static Reply refunded(String paymentId) {
            return json(200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
        }

        public static Reply delayed(long millis, Reply inner) {
            return new Reply(inner.status, inner.body, millis, false);
        }

        public static Reply drop() {
            return new Reply(0, "", 0, true);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile Function<Call, Reply> approveHandler = defaultApprove();
    private volatile Function<Call, Reply> refundHandler = defaultRefund();

    public PgStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/v1/payments", this::handle);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        calls.clear();
        approveHandler = defaultApprove();
        refundHandler = defaultRefund();
    }

    public void onApprove(Function<Call, Reply> handler) {
        this.approveHandler = handler;
    }

    public void onRefund(Function<Call, Reply> handler) {
        this.refundHandler = handler;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> approveCalls() {
        return calls.stream().filter(c -> !c.isRefund()).toList();
    }

    public List<Call> refundCalls() {
        return calls.stream().filter(Call::isRefund).toList();
    }

    private Function<Call, Reply> defaultApprove() {
        return call -> {
            String id = "pg-" + sequence.incrementAndGet();
            String token = call.body() == null ? "" : call.body().path("cardToken").asText("");
            return "tok_decline".equals(token) ? Reply.declined(id) : Reply.approved(id);
        };
    }

    private Function<Call, Reply> defaultRefund() {
        return call -> {
            String[] parts = call.path().split("/");
            return Reply.refunded(parts[parts.length - 2]);
        };
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            JsonNode body = raw.length == 0 ? null : MAPPER.readTree(raw);
            Call call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"), body);
            calls.add(call);
            Reply reply = call.isRefund() ? refundHandler.apply(call) : approveHandler.apply(call);
            if (reply.delayMillis() > 0) {
                Thread.sleep(reply.delayMillis());
            }
            if (reply.dropConnection()) {
                exchange.close();
                return;
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
        } catch (IOException | InterruptedException ignored) {
            // client gave up (timeout) - nothing to do
        } finally {
            exchange.close();
        }
    }
}
