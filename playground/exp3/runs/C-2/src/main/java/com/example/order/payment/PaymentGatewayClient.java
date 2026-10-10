package com.example.order.payment;

import com.example.order.common.PaymentGatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * External PG calls (no automatic retry). Failure classification:
 * timeout -> 504; connection failure / 5xx / 4xx / malformed body -> 502 with a reason.
 * Never logs or stores the card token.
 */
@Component
public class PaymentGatewayClient {

    public record Approval(String paymentId, boolean approved) {
    }

    private record ApproveRequest(long orderId, long amount, String cardToken) {
        @Override
        public String toString() {
            return "ApproveRequest[orderId=" + orderId + ", amount=" + amount + ", cardToken=***]";
        }
    }

    private record RawResponse(int status, String body) {
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public PaymentGatewayClient(@Qualifier("paymentGatewayRestClient") RestClient restClient,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public Approval approve(long orderId, long amount, String cardToken, String idempotencyKey) {
        RawResponse raw = execute(() -> restClient.post().uri("/v1/payments")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApproveRequest(orderId, amount, cardToken))
                .exchange((request, response) -> new RawResponse(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8))));
        JsonNode json = parseOk(raw);
        String paymentId = textOf(json.get("paymentId"));
        String status = textOf(json.get("status"));
        if (paymentId == null || paymentId.isBlank() || paymentId.length() > 100) {
            throw PaymentGatewayException.error("BAD_RESPONSE");
        }
        if ("APPROVED".equals(status)) {
            return new Approval(paymentId, true);
        }
        if ("DECLINED".equals(status)) {
            return new Approval(paymentId, false);
        }
        throw PaymentGatewayException.error("BAD_RESPONSE");
    }

    public void refund(String paymentId) {
        RawResponse raw = execute(() -> restClient.post().uri("/v1/payments/{paymentId}/refund", paymentId)
                .header("Idempotency-Key", "refund-" + paymentId)
                .exchange((request, response) -> new RawResponse(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8))));
        JsonNode json = parseOk(raw);
        if (!"REFUNDED".equals(textOf(json.get("status")))) {
            throw PaymentGatewayException.error("BAD_RESPONSE");
        }
    }

    private RawResponse execute(java.util.function.Supplier<RawResponse> call) {
        try {
            return call.get();
        } catch (RestClientException e) {
            throw isTimeout(e) ? PaymentGatewayException.timeout() : PaymentGatewayException.error("UNAVAILABLE");
        }
    }

    private JsonNode parseOk(RawResponse raw) {
        if (raw.status() >= 500) {
            throw PaymentGatewayException.error("SERVER_ERROR");
        }
        if (raw.status() >= 400) {
            throw PaymentGatewayException.error("REJECTED");
        }
        if (raw.status() != 200) {
            throw PaymentGatewayException.error("BAD_RESPONSE");
        }
        try {
            JsonNode json = objectMapper.readTree(raw.body());
            if (json == null || !json.isObject()) {
                throw PaymentGatewayException.error("BAD_RESPONSE");
            }
            return json;
        } catch (IOException e) {
            throw PaymentGatewayException.error("BAD_RESPONSE");
        }
    }

    private static String textOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() || node.isIntegralNumber() ? node.asText() : null;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
            if (t instanceof ResourceAccessException && t.getCause() == null) {
                return false;
            }
        }
        return false;
    }
}
