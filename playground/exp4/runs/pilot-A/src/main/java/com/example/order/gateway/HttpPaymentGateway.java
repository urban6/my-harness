package com.example.order.gateway;

import com.example.order.common.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Component;

@Component
public class HttpPaymentGateway implements PaymentGateway {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();
    private final ObjectMapper mapper;
    private final String baseUrl;

    public HttpPaymentGateway(AppProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        String url = props.paymentGateway().url();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public ChargeResult charge(String idempotencyKey, long orderId, long amount, String cardToken) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("amount", amount);
        payload.put("cardToken", cardToken);
        JsonNode body = post("/v1/payments", idempotencyKey, payload);
        String status = body.path("status").asText("");
        JsonNode paymentId = body.get("paymentId");
        String id = paymentId == null || paymentId.isNull() ? null : paymentId.asText();
        return switch (status) {
            case "APPROVED" -> new ChargeResult(true, id);
            case "DECLINED" -> new ChargeResult(false, id);
            default -> throw new GatewayUnavailableException("Unexpected gateway status: " + status, null);
        };
    }

    @Override
    public void refund(String paymentId) {
        String encoded = URLEncoder.encode(paymentId, StandardCharsets.UTF_8).replace("+", "%20");
        JsonNode body = post("/v1/payments/" + encoded + "/refund", null, null);
        if (!"REFUNDED".equals(body.path("status").asText(""))) {
            throw new GatewayUnavailableException("Unexpected refund status: " + body.path("status"), null);
        }
    }

    private JsonNode post(String path, String idempotencyKey, Object payload) {
        CompletableFuture<HttpResponse<String>> future = null;
        try {
            String json = payload == null ? "{}" : mapper.writeValueAsString(payload);
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (idempotencyKey != null) {
                req.header("Idempotency-Key", idempotencyKey);
            }
            future = client.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> res = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() != 200) {
                throw new GatewayUnavailableException("Gateway responded " + res.statusCode(), null);
            }
            return mapper.readTree(res.body());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new GatewayUnavailableException("Gateway timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayUnavailableException("Interrupted", e);
        } catch (GatewayUnavailableException e) {
            throw e;
        } catch (ExecutionException | java.io.IOException | RuntimeException e) {
            throw new GatewayUnavailableException("Gateway call failed", e);
        }
    }
}
