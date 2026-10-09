package com.example.order.payment;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 외부 PG 클라이언트. 5xx·연결 실패·타임아웃(기본 2초)·해석할 수 없는 응답은 모두
 * {@link PaymentGatewayUnavailableException}으로 바꾼다(R5.6, R7.3).
 */
@Component
public class PaymentGatewayClient {

    private final RestClient restClient;

    public PaymentGatewayClient(PaymentGatewayProperties properties, RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = builder
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .build();
    }

    public PaymentResult pay(String idempotencyKey, long orderId, long amount, String cardToken) {
        GatewayResponse response = call(() -> restClient.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", orderId, "amount", amount, "cardToken", cardToken))
                .retrieve()
                .body(GatewayResponse.class));
        return switch (response.status()) {
            case "APPROVED" -> new PaymentResult(response.paymentId(), true);
            case "DECLINED" -> new PaymentResult(response.paymentId(), false);
            default -> throw new PaymentGatewayUnavailableException(
                    "PG 결제 응답을 해석할 수 없습니다: status=" + response.status(), null);
        };
    }

    public void refund(String paymentId) {
        GatewayResponse response = call(() -> restClient.post()
                .uri("/v1/payments/{paymentId}/refund", paymentId)
                .retrieve()
                .body(GatewayResponse.class));
        if (!"REFUNDED".equals(response.status())) {
            throw new PaymentGatewayUnavailableException(
                    "PG 환불 응답을 해석할 수 없습니다: status=" + response.status(), null);
        }
    }

    private static GatewayResponse call(Supplier<GatewayResponse> request) {
        GatewayResponse response;
        try {
            response = request.get();
        } catch (RestClientException e) {
            throw new PaymentGatewayUnavailableException("결제 대행사에 연결할 수 없습니다.", e);
        }
        if (response == null || response.status() == null) {
            throw new PaymentGatewayUnavailableException("PG 응답 본문이 비어 있습니다.", null);
        }
        return response;
    }

    record GatewayResponse(String paymentId, String status) {
    }
}
