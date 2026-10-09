package com.example.order.payment;

import com.example.order.common.error.PaymentGatewayUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 외부 PG 호출. 모든 실패(5xx·4xx·연결 실패·타임아웃·비정상 응답)는 PaymentGatewayUnavailableException. */
@Component
public class PaymentGatewayClient {

    private final RestClient restClient;

    public PaymentGatewayClient(PaymentGatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.url().toString())
                .requestFactory(factory)
                .build();
    }

    public PgPayResult pay(String idempotencyKey, long orderId, long amount, String cardToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("amount", amount);
        body.put("cardToken", cardToken);
        JsonNode node;
        try {
            node = restClient.post()
                    .uri("/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PaymentGatewayUnavailableException("PG 결제 요청 실패: " + e.getClass().getSimpleName());
        }
        String paymentId = textOrNull(node, "paymentId");
        String status = textOrNull(node, "status");
        if (paymentId == null || status == null) {
            throw new PaymentGatewayUnavailableException("PG 응답을 해석할 수 없습니다.");
        }
        return switch (status) {
            case "APPROVED" -> new PgPayResult(paymentId, true);
            case "DECLINED" -> new PgPayResult(paymentId, false);
            default -> throw new PaymentGatewayUnavailableException("PG 응답 status가 올바르지 않습니다.");
        };
    }

    public PgRefundResult refund(String paymentId) {
        JsonNode node;
        try {
            node = restClient.post()
                    .uri("/v1/payments/{paymentId}/refund", paymentId)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PaymentGatewayUnavailableException("PG 환불 요청 실패: " + e.getClass().getSimpleName());
        }
        if (!"REFUNDED".equals(textOrNull(node, "status"))) {
            throw new PaymentGatewayUnavailableException("PG 환불 응답 status가 올바르지 않습니다.");
        }
        return new PgRefundResult(paymentId);
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }
}
