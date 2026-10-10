package com.example.order.payment;

import java.net.http.HttpClient;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 외부 PG 클라이언트. 5xx·연결 실패·타임아웃(기본 2초)은 모두 {@link PaymentGatewayUnavailableException}이다.
 */
@Component
public class PaymentGatewayClient {

    private final RestClient restClient;

    public PaymentGatewayClient(RestClient.Builder builder, PaymentGatewayProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = builder
                .baseUrl(properties.url().toString())
                .requestFactory(requestFactory)
                .build();
    }

    public PaymentResult pay(long orderId, long amount, String cardToken, String idempotencyKey) {
        GatewayResponse response = call(() -> restClient.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaymentRequest(orderId, amount, cardToken))
                .retrieve()
                .body(GatewayResponse.class));
        return switch (response.status()) {
            case "APPROVED" -> new PaymentResult(response.paymentId(), true);
            case "DECLINED" -> new PaymentResult(response.paymentId(), false);
            default -> throw unexpected(response);
        };
    }

    public void refund(String paymentId) {
        GatewayResponse response = call(() -> restClient.post()
                .uri("/v1/payments/{paymentId}/refund", paymentId)
                .retrieve()
                .body(GatewayResponse.class));
        if (!"REFUNDED".equals(response.status())) {
            throw unexpected(response);
        }
    }

    private static GatewayResponse call(Supplier<GatewayResponse> request) {
        GatewayResponse response;
        try {
            response = request.get();
        } catch (RestClientException e) {
            throw new PaymentGatewayUnavailableException("결제 대행사와 통신하지 못했습니다.", e);
        }
        if (response == null || response.status() == null) {
            throw unexpected(response);
        }
        return response;
    }

    private static PaymentGatewayUnavailableException unexpected(GatewayResponse response) {
        return new PaymentGatewayUnavailableException("결제 대행사가 알 수 없는 응답을 보냈습니다: " + response, null);
    }

    private record PaymentRequest(long orderId, long amount, String cardToken) {
    }

    private record GatewayResponse(String paymentId, String status) {
    }
}
