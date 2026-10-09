package com.example.order.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentGatewayClient {

    public record PaymentResult(String paymentId, boolean approved) {
    }

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;

    public PaymentGatewayClient(ObjectMapper objectMapper,
                                @Value("${payment.gateway.url}") String baseUrl,
                                @Value("${payment.gateway.timeout:PT2S}") Duration timeout) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    public PaymentResult pay(String idempotencyKey, long orderId, long amount, String cardToken) {
        String body = toJson(Map.of("orderId", orderId, "amount", amount, "cardToken", cardToken));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payments"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        JsonNode response = send(request);
        String status = response.path("status").asText();
        String paymentId = response.path("paymentId").isNull() ? null : response.path("paymentId").asText(null);
        return switch (status) {
            case "APPROVED" -> new PaymentResult(paymentId, true);
            case "DECLINED" -> new PaymentResult(paymentId, false);
            default -> throw new PaymentGatewayUnavailableException("Unexpected payment status: " + status);
        };
    }

    public void refund(String paymentId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payments/" + paymentId + "/refund"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        JsonNode response = send(request);
        if (!"REFUNDED".equals(response.path("status").asText())) {
            throw new PaymentGatewayUnavailableException("Unexpected refund status: " + response.path("status"));
        }
    }

    private JsonNode send(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            // 응답 본문까지 포함해 전체 제한 시간을 지킨다.
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PaymentGatewayUnavailableException("Payment gateway timed out", e);
        } catch (ExecutionException e) {
            throw new PaymentGatewayUnavailableException("Payment gateway connection failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentGatewayUnavailableException("Interrupted while calling payment gateway", e);
        }
        if (response.statusCode() != 200) {
            throw new PaymentGatewayUnavailableException("Payment gateway responded " + response.statusCode());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new PaymentGatewayUnavailableException("Unreadable payment gateway response", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
