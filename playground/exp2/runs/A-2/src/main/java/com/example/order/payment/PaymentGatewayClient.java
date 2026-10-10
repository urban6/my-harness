package com.example.order.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 외부 PG 클라이언트. 응답 전체가 제한 시간(기본 2초) 안에 오지 않으면 장애로 본다. */
@Component
public class PaymentGatewayClient {

    public enum PaymentStatus { APPROVED, DECLINED }

    public record PaymentResult(String paymentId, PaymentStatus status) {
    }

    private final String baseUrl;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public PaymentGatewayClient(@Value("${payment.gateway.url}") String baseUrl,
                                @Value("${payment.gateway.timeout}") Duration timeout,
                                ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    public PaymentResult pay(String idempotencyKey, long orderId, long amount, String cardToken) {
        JsonNode body = post("/v1/payments", idempotencyKey,
                Map.of("orderId", orderId, "amount", amount, "cardToken", cardToken));
        String status = body.path("status").asText();
        String paymentId = body.path("paymentId").asText(null);
        return switch (status) {
            case "APPROVED" -> new PaymentResult(paymentId, PaymentStatus.APPROVED);
            case "DECLINED" -> new PaymentResult(paymentId, PaymentStatus.DECLINED);
            default -> throw new PaymentGatewayUnavailableException("Unexpected payment status: " + status, null);
        };
    }

    public void refund(String paymentId) {
        JsonNode body = post("/v1/payments/" + paymentId + "/refund", null, Map.of());
        if (!"REFUNDED".equals(body.path("status").asText())) {
            throw new PaymentGatewayUnavailableException("Unexpected refund status: " + body.path("status"), null);
        }
    }

    private JsonNode post(String path, String idempotencyKey, Map<String, Object> payload) {
        CompletableFuture<HttpResponse<String>> future = null;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            future = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new PaymentGatewayUnavailableException("PG responded " + response.statusCode(), null);
            }
            return objectMapper.readTree(response.body());
        } catch (PaymentGatewayUnavailableException e) {
            throw e;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PaymentGatewayUnavailableException("PG timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentGatewayUnavailableException("Interrupted while calling PG", e);
        } catch (ExecutionException | RuntimeException | java.io.IOException e) {
            throw new PaymentGatewayUnavailableException("PG call failed", e);
        }
    }
}
