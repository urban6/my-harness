package com.example.order.payment;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.order.common.ApiException;

@Component
public class HttpPaymentGateway implements PaymentGateway {

    private final RestClient client;

    public HttpPaymentGateway(RestClient.Builder builder,
            @Value("${payment.gateway.url}") String baseUrl,
            @Value("${payment.gateway.connect-timeout}") Duration connectTimeout,
            @Value("${payment.gateway.read-timeout}") Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public PaymentResult charge(long orderId, long amount, String cardToken, String idempotencyKey) {
        ChargeResponse res;
        try {
            res = client.post()
                    .uri("/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ChargeRequest(orderId, amount, cardToken))
                    .retrieve()
                    .body(ChargeResponse.class);
        } catch (RestClientException e) {
            throw ApiException.badGateway("payment gateway request failed: " + e.getMessage());
        }
        if (res == null || res.paymentId() == null || res.status() == null) {
            throw ApiException.badGateway("payment gateway returned an empty response");
        }
        return switch (res.status()) {
            case "APPROVED" -> new PaymentResult(res.paymentId(), true);
            case "DECLINED" -> new PaymentResult(res.paymentId(), false);
            default -> throw ApiException.badGateway("unexpected payment status: " + res.status());
        };
    }

    @Override
    public void refund(String paymentId) {
        RefundResponse res;
        try {
            res = client.post()
                    .uri("/v1/payments/{paymentId}/refund", paymentId)
                    .retrieve()
                    .body(RefundResponse.class);
        } catch (RestClientException e) {
            throw ApiException.badGateway("payment gateway refund failed: " + e.getMessage());
        }
        if (res == null || !"REFUNDED".equals(res.status())) {
            throw ApiException.badGateway("refund was not confirmed by the payment gateway");
        }
    }

    record ChargeRequest(long orderId, long amount, String cardToken) {
    }

    record ChargeResponse(String paymentId, String status) {
    }

    record RefundResponse(String paymentId, String status) {
    }
}
