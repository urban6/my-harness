package com.example.order.payment;

import com.example.order.config.PaymentGatewayProperties;
import java.net.http.HttpClient;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpPaymentGateway implements PaymentGateway {

    private final RestClient client;

    public HttpPaymentGateway(PaymentGatewayProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        this.client = RestClient.builder().baseUrl(props.url()).requestFactory(factory).build();
    }

    private record ChargeResponse(String paymentId, String status) {
    }

    private record RefundResponse(String paymentId, String status) {
    }

    @Override
    public ChargeResult charge(long orderId, long amount, String cardToken, String idempotencyKey) {
        ChargeResponse res;
        try {
            res = client.post().uri("/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(Map.of("orderId", orderId, "amount", amount, "cardToken", cardToken))
                    .retrieve().body(ChargeResponse.class);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("Payment gateway call failed: " + e.getMessage(), e);
        }
        if (res == null || res.paymentId() == null || res.status() == null) {
            throw new PaymentGatewayException("Payment gateway returned an empty response");
        }
        try {
            return new ChargeResult(res.paymentId(), ChargeStatus.valueOf(res.status()));
        } catch (IllegalArgumentException e) {
            throw new PaymentGatewayException("Unknown payment status: " + res.status(), e);
        }
    }

    @Override
    public void refund(String paymentId) {
        RefundResponse res;
        try {
            res = client.post().uri("/v1/payments/{paymentId}/refund", paymentId)
                    .retrieve().body(RefundResponse.class);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("Refund call failed: " + e.getMessage(), e);
        }
        if (res == null || !"REFUNDED".equals(res.status())) {
            throw new PaymentGatewayException("Refund was not confirmed by the payment gateway");
        }
    }
}
