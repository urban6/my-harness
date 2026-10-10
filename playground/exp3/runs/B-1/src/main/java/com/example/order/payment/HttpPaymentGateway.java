package com.example.order.payment;

import java.net.http.HttpClient;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 로그·예외 메시지에 cardToken 을 남기지 않는다. */
@Component
public class HttpPaymentGateway implements PaymentGateway {

    private final RestClient client;

    public HttpPaymentGateway(PaymentProperties properties, RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = builder.baseUrl(properties.gatewayUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public PaymentResult charge(String idempotencyKey, long orderId, long amount, String cardToken) {
        ChargeResponse response;
        try {
            response = client.post()
                    .uri("/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ChargeRequest(orderId, amount, cardToken))
                    .retrieve()
                    .body(ChargeResponse.class);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("결제 요청 실패: orderId=" + orderId, e);
        }
        if (response == null || response.status() == null) {
            throw new PaymentGatewayException("결제 응답이 비어 있습니다: orderId=" + orderId);
        }
        return switch (response.status()) {
            case "APPROVED" -> {
                if (response.paymentId() == null) {
                    throw new PaymentGatewayException("승인 응답에 paymentId 가 없습니다: orderId=" + orderId);
                }
                yield new PaymentResult(response.paymentId(), true);
            }
            case "DECLINED" -> new PaymentResult(response.paymentId(), false);
            default -> throw new PaymentGatewayException("알 수 없는 결제 상태: " + response.status());
        };
    }

    @Override
    public void refund(String paymentId) {
        RefundResponse response;
        try {
            response = client.post()
                    .uri("/v1/payments/{paymentId}/refund", paymentId)
                    .retrieve()
                    .body(RefundResponse.class);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("환불 요청 실패: paymentId=" + paymentId, e);
        }
        if (response == null || !"REFUNDED".equals(response.status())) {
            throw new PaymentGatewayException("환불이 완료되지 않았습니다: paymentId=" + paymentId);
        }
    }

    private record ChargeRequest(long orderId, long amount, String cardToken) {}

    private record ChargeResponse(String paymentId, String status) {}

    private record RefundResponse(String paymentId, String status) {}
}
