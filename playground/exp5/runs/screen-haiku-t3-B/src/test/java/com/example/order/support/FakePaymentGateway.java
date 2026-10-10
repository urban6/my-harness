package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process stand-in for the external PG (feature.md + change-request.md contract). A payment's behaviour is chosen by
 * its cardToken: ok, decline, fail (5xx) or slow (past the 2 s limit). Refunds answer 5xx while the refund mode is
 * "fail", and a refund with an idempotency key already seen returns the first result. Every call is recorded.
 */
public final class FakePaymentGateway {

    public record Charge(String idempotencyKey, long amount) {
    }

    public record Refund(String paymentId, String idempotencyKey, long amount) {
    }

    private record Reply(int status, String body) {
    }

    private final HttpServer server;
    private final ObjectMapper om = new ObjectMapper();
    private final AtomicInteger paymentCalls = new AtomicInteger();
    private final AtomicReference<String> refundMode = new AtomicReference<>("ok");
    private final List<Charge> charges = new CopyOnWriteArrayList<>();
    private final List<Refund> refunds = new CopyOnWriteArrayList<>();
    /** Guarded by {@code refunds}. */
    private final Map<String, String> refundReplies = new HashMap<>();
    /** Guarded by {@code refunds}: approved payment id to amount. */
    private final Map<String, Long> approved = new HashMap<>();

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

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    public void setRefundMode(String mode) {
        refundMode.set(mode);
    }

    public int paymentCount() {
        return paymentCalls.get();
    }

    /** Sum of the amounts charged under this idempotency key. */
    public long chargedFor(String idempotencyKey) {
        return charges.stream().filter(c -> c.idempotencyKey().equals(idempotencyKey))
                .mapToLong(Charge::amount).sum();
    }

    /** Sum of the amounts refunded under this idempotency key (0 when no refund was made with it). */
    public long refundedFor(String idempotencyKey) {
        return refunds.stream().filter(r -> r.idempotencyKey().equals(idempotencyKey))
                .mapToLong(Refund::amount).sum();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        JsonNode req = om.readTree(body);
        Reply reply = path.endsWith("/refund")
                ? refundReply(path.split("/")[3], key, req.path("amount").asLong())
                : paymentReply(key, req);
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(reply.status(), bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // client gave up (timeout test)
        }
        ex.close();
    }

    private Reply paymentReply(String key, JsonNode req) {
        paymentCalls.incrementAndGet();
        long amount = req.path("amount").asLong();
        charges.add(new Charge(key, amount));
        String paymentId = "pay-" + UUID.randomUUID();
        return switch (req.path("cardToken").asText()) {
            case "decline" -> new Reply(200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"DECLINED\"}");
            case "fail" -> new Reply(500, "{}");
            case "slow" -> {
                sleep(3000);
                yield approved(paymentId, amount);
            }
            default -> {
                sleep(300);
                yield approved(paymentId, amount);
            }
        };
    }

    private Reply approved(String paymentId, long amount) {
        synchronized (refunds) {
            approved.put(paymentId, amount);
        }
        return new Reply(200, "{\"paymentId\":\"" + paymentId + "\",\"status\":\"APPROVED\"}");
    }

    private Reply refundReply(String paymentId, String key, long amount) {
        if ("fail".equals(refundMode.get())) {
            return new Reply(500, "{}");
        }
        synchronized (refunds) {
            String first = refundReplies.get(key);
            if (first != null) {
                return new Reply(200, first);
            }
            long paid = approved.getOrDefault(paymentId, 0L);
            long refunded = refunds.stream().filter(r -> r.paymentId().equals(paymentId))
                    .mapToLong(Refund::amount).sum();
            if (refunded + amount > paid) {
                return new Reply(400, "{\"code\":\"REFUND_EXCEEDS_PAYMENT\"}");
            }
            refunds.add(new Refund(paymentId, key, amount));
            String out = "{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\",\"amount\":" + amount + "}";
            refundReplies.put(key, out);
            return new Reply(200, out);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
