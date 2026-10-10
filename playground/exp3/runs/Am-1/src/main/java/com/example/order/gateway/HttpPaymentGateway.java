package com.example.order.gateway;

import com.example.order.config.AppProperties;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpPaymentGateway implements PaymentGateway {

    private record ChargeResponse(String paymentId, String status) {
    }

    private record RefundResponse(String paymentId, String status) {
    }

    private final RestClient client;

    public HttpPaymentGateway(AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(props.paymentGatewayUrl()).requestFactory(factory).build();
    }

    @Override
    public PaymentResult charge(String idempotencyKey, long orderId, long amount, String cardToken) {
        ChargeResponse res;
        try {
            res = client.post().uri("/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(Map.of("orderId", orderId, "amount", amount, "cardToken", cardToken))
                    .retrieve().body(ChargeResponse.class);
        } catch (RuntimeException e) {
            throw new PaymentGatewayException("payment gateway call failed", e);
        }
        if (res == null || res.status() == null) {
            throw new PaymentGatewayException("empty payment gateway response");
        }
        return switch (res.status()) {
            case "APPROVED" -> new PaymentResult(requirePaymentId(res.paymentId()), true);
            case "DECLINED" -> new PaymentResult(res.paymentId(), false);
            default -> throw new PaymentGatewayException("unknown payment status " + res.status());
        };
    }

    @Override
    public void refund(String paymentId) {
        RefundResponse res;
        try {
            res = client.post().uri("/v1/payments/{id}/refund", paymentId)
                    .retrieve().body(RefundResponse.class);
        } catch (RuntimeException e) {
            throw new PaymentGatewayException("refund call failed", e);
        }
        if (res == null || !"REFUNDED".equals(res.status())) {
            throw new PaymentGatewayException("refund not confirmed");
        }
    }

    private static String requirePaymentId(String id) {
        if (id == null || id.isBlank()) {
            throw new PaymentGatewayException("approved payment without paymentId");
        }
        return id;
    }
}
