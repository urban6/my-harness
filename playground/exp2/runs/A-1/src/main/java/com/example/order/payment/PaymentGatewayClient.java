package com.example.order.payment;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 외부 PG 클라이언트. 5xx·연결 실패·타임아웃(기본 2초)·해석할 수 없는 응답은 모두 PG 장애(503)로 본다.
 */
@Component
public class PaymentGatewayClient {

    private final RestClient restClient;

    public PaymentGatewayClient(RestClient.Builder builder,
                                @Value("${payment.gateway.url}") String baseUrl,
                                @Value("${payment.gateway.timeout:PT2S}") Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public PaymentResult pay(String idempotencyKey, long orderId, long amount, String cardToken) {
        PaymentResponse response = call(() -> restClient.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaymentRequest(orderId, amount, cardToken))
                .retrieve()
                .body(PaymentResponse.class));
        if (response == null || response.status() == null) {
            throw unavailable("empty payment response");
        }
        return switch (response.status()) {
            case "APPROVED" -> new PaymentResult(response.paymentId(), true);
            case "DECLINED" -> new PaymentResult(response.paymentId(), false);
            default -> throw unavailable("unknown payment status " + response.status());
        };
    }

    public void refund(String paymentId) {
        PaymentResponse response = call(() -> restClient.post()
                .uri("/v1/payments/{paymentId}/refund", paymentId)
                .retrieve()
                .body(PaymentResponse.class));
        if (response == null || !"REFUNDED".equals(response.status())) {
            throw unavailable("unexpected refund response");
        }
    }

    private static PaymentResponse call(Supplier<PaymentResponse> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw unavailable(e.getMessage());
        }
    }

    private static ApiException unavailable(String reason) {
        return new ApiException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "Payment gateway unavailable: " + reason);
    }

    record PaymentRequest(long orderId, long amount, String cardToken) {
    }

    record PaymentResponse(String paymentId, String status) {
    }

    public record PaymentResult(String paymentId, boolean approved) {
    }
}
