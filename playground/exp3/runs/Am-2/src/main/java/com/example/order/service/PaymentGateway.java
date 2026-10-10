package com.example.order.service;

import com.example.order.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.net.http.HttpClient;
import java.time.Duration;

/** Client for the external PG. Any transport/HTTP failure surfaces as 502 and leaves local state untouched. */
@Component
public class PaymentGateway {

    public record Payment(String paymentId, String status) {
        public boolean approved() { return "APPROVED".equals(status); }
    }

    private record ChargeRequest(Long orderId, long amount, String cardToken) {
    }

    private final RestClient client;

    public PaymentGateway(@Value("${payment.gateway.url}") String baseUrl) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Payment charge(Long orderId, long amount, String cardToken, String idempotencyKey) {
        Payment p = call(() -> client.post().uri("/v1/payments")
                .header("Idempotency-Key", idempotencyKey)
                .body(new ChargeRequest(orderId, amount, cardToken))
                .retrieve().body(Payment.class));
        if (p == null || p.paymentId() == null || (!p.approved() && !"DECLINED".equals(p.status()))) {
            throw ApiException.badGateway("Unexpected payment gateway response");
        }
        return p;
    }

    public void refund(String paymentId) {
        Payment p = call(() -> client.post().uri("/v1/payments/{id}/refund", paymentId)
                .retrieve().body(Payment.class));
        if (p == null || !"REFUNDED".equals(p.status())) {
            throw ApiException.badGateway("Unexpected payment gateway response");
        }
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw ApiException.badGateway("Payment gateway call failed: " + e.getMessage());
        }
    }
}
