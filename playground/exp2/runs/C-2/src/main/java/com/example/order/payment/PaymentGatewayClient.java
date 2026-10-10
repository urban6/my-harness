package com.example.order.payment;

import com.example.order.common.config.PaymentGatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 외부 PG 클라이언트. HTTP를 모르는 결과 타입만 돌려준다.
 * 5xx / 4xx / 연결 실패 / 읽기 타임아웃(기본 2초) / 계약 밖 응답은 모두 PaymentGatewayUnavailableException.
 */
@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);

    // PG 응답은 필드 타입(paymentId가 숫자/문자열)에 관대하게 JsonNode로 읽는다.
    private final ObjectMapper lenientMapper = new ObjectMapper();
    private final RestClient restClient;

    public PaymentGatewayClient(PaymentGatewayProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.url().toString())
                .requestFactory(factory)
                .build();
    }

    /** POST /v1/payments. idempotencyKey는 클라이언트가 보낸 Idempotency-Key 그대로 (R5.3). */
    public PaymentResult pay(String idempotencyKey, long orderId, long amount, String cardToken) {
        try {
            String body = restClient.post()
                    .uri("/v1/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(Map.of("orderId", orderId, "amount", amount, "cardToken", cardToken))
                    .retrieve()
                    .body(String.class);
            JsonNode node = parse(body);
            String paymentId = text(node, "paymentId");
            String status = text(node, "status");
            if (paymentId == null || status == null) {
                throw unavailable("PG 응답에 paymentId/status가 없습니다", null);
            }
            return switch (status) {
                case "APPROVED" -> PaymentResult.approved(paymentId);
                case "DECLINED" -> PaymentResult.declined(paymentId);
                default -> throw unavailable("알 수 없는 PG 결제 상태: " + status, null);
            };
        } catch (PaymentGatewayUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("PG 결제 요청 실패", e);
        }
    }

    /** POST /v1/payments/{paymentId}/refund. 200 + REFUNDED 외에는 모두 장애. */
    public void refund(String paymentId) {
        try {
            String body = restClient.post()
                    .uri("/v1/payments/{paymentId}/refund", paymentId)
                    .retrieve()
                    .body(String.class);
            String status = text(parse(body), "status");
            if (!"REFUNDED".equals(status)) {
                throw unavailable("알 수 없는 PG 환불 상태: " + status, null);
            }
        } catch (PaymentGatewayUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("PG 환불 요청 실패", e);
        }
    }

    private JsonNode parse(String body) {
        try {
            return lenientMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw unavailable("PG 응답을 해석할 수 없습니다", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? null : v.asText();
    }

    private static PaymentGatewayUnavailableException unavailable(String message, Throwable cause) {
        log.warn("{}: {}", message, cause == null ? "-" : cause.toString());
        return new PaymentGatewayUnavailableException("결제 대행사를 사용할 수 없습니다.");
    }
}
