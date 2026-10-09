package com.example.order.payment;

import java.net.http.HttpClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestPaymentGatewayClient implements PaymentGatewayClient {

    record PayRequest(long orderId, long amount, String cardToken) {
    }

    record PayResponse(String paymentId, String status) {
    }

    record RefundResponse(String paymentId, String status) {
    }

    private final RestClient restClient;

    public RestPaymentGatewayClient(PaymentGatewayProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(props.url().toString())
                .requestFactory(factory)
                .build();
    }

    @Override
    public PgPaymentResult pay(String idempotencyKey, long orderId, long amount, String cardToken) {
        PayResponse res;
        try {
            res = restClient.post()
                    .uri("/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PayRequest(orderId, amount, cardToken))
                    .retrieve()
                    .body(PayResponse.class);
        } catch (RuntimeException e) {
            throw new PaymentGatewayUnavailableException("payment request failed", e);
        }
        if (res == null || res.paymentId() == null || res.status() == null) {
            throw new PaymentGatewayUnavailableException("invalid payment response", null);
        }
        return switch (res.status()) {
            case "APPROVED" -> new PgPaymentResult(res.paymentId(), true);
            case "DECLINED" -> new PgPaymentResult(res.paymentId(), false);
            default -> throw new PaymentGatewayUnavailableException("unknown payment status " + res.status(), null);
        };
    }

    @Override
    public void refund(String paymentId) {
        RefundResponse res;
        try {
            res = restClient.post()
                    .uri(b -> b.path("/v1/payments/{paymentId}/refund").build(paymentId))
                    .retrieve()
                    .body(RefundResponse.class);
        } catch (RuntimeException e) {
            throw new PaymentGatewayUnavailableException("refund request failed", e);
        }
        if (res == null || !"REFUNDED".equals(res.status())) {
            throw new PaymentGatewayUnavailableException("invalid refund response", null);
        }
    }
}
