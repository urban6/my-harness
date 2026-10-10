package com.example.order.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 외부 PG 연동. 5xx·연결 실패·시간 초과는 모두 {@link PaymentGatewayUnavailableException}. */
@Component
public class PaymentGatewayClient {

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;

    public PaymentGatewayClient(PaymentGatewayProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.baseUrl = properties.url().replaceAll("/+$", "");
        this.timeout = properties.timeout();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    public PaymentResult pay(String idempotencyKey, long orderId, long amount, String cardToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("amount", amount);
        body.put("cardToken", cardToken);
        JsonNode response = post("/v1/payments", idempotencyKey, body);
        String status = response.path("status").asText("");
        String paymentId = response.path("paymentId").asText(null);
        return switch (status) {
            case "APPROVED" -> {
                if (paymentId == null || paymentId.isBlank()) {
                    throw new PaymentGatewayUnavailableException("PG approved without paymentId");
                }
                yield new PaymentResult(paymentId, true);
            }
            case "DECLINED" -> new PaymentResult(paymentId, false);
            default -> throw new PaymentGatewayUnavailableException("Unexpected PG payment status: " + status);
        };
    }

    public void refund(String paymentId) {
        String path = "/v1/payments/" + URLEncoder.encode(paymentId, StandardCharsets.UTF_8).replace("+", "%20") + "/refund";
        JsonNode response = post(path, null, Map.of());
        String status = response.path("status").asText("");
        if (!"REFUNDED".equals(status)) {
            throw new PaymentGatewayUnavailableException("Unexpected PG refund status: " + status);
        }
    }

    private JsonNode post(String path, String idempotencyKey, Object body) {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        } catch (IOException | IllegalArgumentException e) {
            throw new PaymentGatewayUnavailableException("Cannot build PG request", e);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        HttpResponse<String> response = send(builder.build());
        int status = response.statusCode();
        if (status != 200) {
            throw new PaymentGatewayUnavailableException("PG responded with HTTP " + status);
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new PaymentGatewayUnavailableException("PG response is not valid JSON", e);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            // 연결부터 본문 수신까지 전체를 timeout 안에 끝내야 한다
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PaymentGatewayUnavailableException("PG did not respond within " + timeout, e);
        } catch (ExecutionException e) {
            throw new PaymentGatewayUnavailableException("PG request failed: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new PaymentGatewayUnavailableException("Interrupted while calling PG", e);
        }
    }
}
