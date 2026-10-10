package com.example.order.payment;

import com.example.order.config.OrderPaymentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * External PG over HTTP. Never retries internally. Must be called outside any DB transaction.
 * Timeouts -> GatewayException(TIMEOUT); everything else abnormal -> GatewayException(ERROR).
 */
@Component
public class PaymentGatewayClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public PaymentGatewayClient(RestClient.Builder builder, ObjectMapper objectMapper, OrderPaymentProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.gateway().connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.gateway().readTimeout());
        this.client = builder.baseUrl(props.gateway().url()).requestFactory(factory).build();
        this.objectMapper = objectMapper;
    }

    /** POST /v1/payments. */
    public GatewayResult charge(long orderId, long amount, String cardToken, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("amount", amount);
        body.put("cardToken", cardToken);
        JsonNode json = post(client.post().uri("/v1/payments").header("Idempotency-Key", idempotencyKey), body);
        String status = text(json, "status");
        String paymentId = text(json, "paymentId");
        if ("APPROVED".equals(status)) {
            if (paymentId == null || paymentId.isBlank() || paymentId.length() > 128) {
                throw new GatewayException(GatewayException.Kind.ERROR, "PG APPROVED without a usable paymentId", null);
            }
            return new GatewayResult(paymentId, true);
        }
        if ("DECLINED".equals(status)) {
            return new GatewayResult(paymentId, false);
        }
        throw new GatewayException(GatewayException.Kind.ERROR, "PG returned unexpected status", null);
    }

    /** POST /v1/payments/{paymentId}/refund. */
    public GatewayResult refund(String paymentId, String idempotencyKey) {
        JsonNode json = post(client.post().uri("/v1/payments/{paymentId}/refund", paymentId)
                .header("Idempotency-Key", idempotencyKey), null);
        if (!"REFUNDED".equals(text(json, "status"))) {
            throw new GatewayException(GatewayException.Kind.ERROR, "PG returned unexpected refund status", null);
        }
        return new GatewayResult(paymentId, true);
    }

    private JsonNode post(RestClient.RequestBodySpec spec, Object body) {
        try {
            RestClient.RequestBodySpec prepared = spec.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                prepared = prepared.body(body);
            }
            return prepared.exchange((request, response) -> {
                int code = response.getStatusCode().value();
                if (code < 200 || code >= 300) {
                    throw new GatewayException(GatewayException.Kind.ERROR, "PG responded with HTTP " + code, null);
                }
                try {
                    JsonNode node = objectMapper.readTree(response.getBody());
                    if (node == null || !node.isObject()) {
                        throw new GatewayException(GatewayException.Kind.ERROR, "PG response is not a JSON object", null);
                    }
                    return node;
                } catch (IOException e) {
                    throw classify(e);
                }
            });
        } catch (GatewayException e) {
            throw e;
        } catch (RestClientException e) {
            throw classify(e);
        }
    }

    private static GatewayException classify(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SocketTimeoutException || c instanceof HttpTimeoutException) {
                return new GatewayException(GatewayException.Kind.TIMEOUT, "PG call timed out", t);
            }
        }
        return new GatewayException(GatewayException.Kind.ERROR, "PG call failed: " + t.getClass().getSimpleName(), t);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
