package com.example.order.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);

    private record PgPaymentRequest(long orderId, long amount, String cardToken) {
    }

    private final RestClient restClient;

    public PaymentGatewayClient(RestClient pgRestClient) {
        this.restClient = pgRestClient;
    }

    public PgPaymentResult pay(long orderId, long amount, String cardToken, String idempotencyKey) {
        try {
            JsonNode node = restClient.post()
                    .uri("/v1/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new PgPaymentRequest(orderId, amount, cardToken))
                    .retrieve()
                    .body(JsonNode.class);
            if (node == null || !node.hasNonNull("paymentId") || !node.hasNonNull("status")) {
                throw new PaymentGatewayUnavailableException("PG 응답이 계약과 다릅니다.", null);
            }
            String paymentId = node.get("paymentId").asText();
            PgPaymentStatus status;
            try {
                status = PgPaymentStatus.valueOf(node.get("status").asText());
            } catch (IllegalArgumentException e) {
                throw new PaymentGatewayUnavailableException("PG 응답 status가 계약과 다릅니다.", e);
            }
            return new PgPaymentResult(paymentId, status);
        } catch (PaymentGatewayUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("PG payment call failed: {}", e.toString());
            throw new PaymentGatewayUnavailableException("결제 대행사를 사용할 수 없습니다.", e);
        }
    }

    public void refund(String paymentId) {
        try {
            JsonNode node = restClient.post()
                    .uri("/v1/payments/{paymentId}/refund", paymentId)
                    .retrieve()
                    .body(JsonNode.class);
            if (node == null || !"REFUNDED".equals(node.path("status").asText(null))) {
                throw new PaymentGatewayUnavailableException("PG 환불 응답이 계약과 다릅니다.", null);
            }
        } catch (PaymentGatewayUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("PG refund call failed: {}", e.toString());
            throw new PaymentGatewayUnavailableException("결제 대행사를 사용할 수 없습니다.", e);
        }
    }
}
