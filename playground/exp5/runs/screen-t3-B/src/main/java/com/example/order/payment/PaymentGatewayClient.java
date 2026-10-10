package com.example.order.payment;

import com.example.order.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * External PG. Any 5xx, connection failure, 2-second timeout (whole exchange) or unusable response is reported as
 * 503 PAYMENT_GATEWAY_UNAVAILABLE.
 */
@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);
    static final Duration TIMEOUT = Duration.ofSeconds(2);

    public enum PaymentStatus { APPROVED, DECLINED }

    public record PaymentResult(String paymentId, PaymentStatus status) {
    }

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    public PaymentGatewayClient(@Value("${app.payment-gateway-url}") String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
    }

    public PaymentResult pay(long orderId, long amount, String cardToken, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("amount", amount);
        body.put("cardToken", cardToken);
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payments"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw ApiException.gatewayUnavailable("payment gateway request could not be built");
        }
        JsonNode json = exchange(req);
        String status = json.path("status").asText("");
        String paymentId = json.path("paymentId").isMissingNode() || json.path("paymentId").isNull()
                ? null : json.path("paymentId").asText();
        return switch (status) {
            case "APPROVED" -> new PaymentResult(paymentId, PaymentStatus.APPROVED);
            case "DECLINED" -> new PaymentResult(paymentId, PaymentStatus.DECLINED);
            default -> throw ApiException.gatewayUnavailable("payment gateway returned unknown status");
        };
    }

    /** Refunds {@code amount} of the payment; the PG replays the first result for a repeated idempotency key. */
    public void refund(String paymentId, long amount, String idempotencyKey) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v1/payments/" + encodePath(paymentId) + "/refund"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("amount", amount))))
                    .build();
        } catch (Exception e) {
            throw ApiException.gatewayUnavailable("payment gateway request could not be built");
        }
        JsonNode json = exchange(req);
        if (!"REFUNDED".equals(json.path("status").asText(""))) {
            throw ApiException.gatewayUnavailable("payment gateway did not confirm the refund");
        }
    }

    private JsonNode exchange(HttpRequest req) {
        CompletableFuture<HttpResponse<String>> future = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> res;
        try {
            res = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ApiException.gatewayUnavailable("payment gateway call interrupted");
        } catch (Exception e) {
            future.cancel(true);
            log.warn("payment gateway call failed: {} {}", req.uri(), e.toString());
            throw ApiException.gatewayUnavailable("payment gateway is unavailable");
        }
        if (res.statusCode() / 100 != 2) {
            log.warn("payment gateway returned {} for {}", res.statusCode(), req.uri());
            throw ApiException.gatewayUnavailable("payment gateway responded with status " + res.statusCode());
        }
        try {
            return mapper.readTree(res.body());
        } catch (Exception e) {
            throw ApiException.gatewayUnavailable("payment gateway returned an unreadable response");
        }
    }

    private static String encodePath(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
