package com.example.order.payment;

import java.net.http.HttpClient;

import com.example.order.common.error.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);

    private final RestClient client;

    public HttpPaymentGateway(PaymentProperties properties, RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.client = builder.baseUrl(properties.url()).requestFactory(factory).build();
    }

    @Override
    public ChargeResult charge(long orderId, long amount, String cardToken, String idempotencyKey) {
        try {
            ChargeResponse body = client.post().uri("/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ChargeRequest(orderId, amount, cardToken))
                    .retrieve()
                    .body(ChargeResponse.class);
            if (body == null || body.paymentId() == null) {
                throw invalidResponse();
            }
            return switch (String.valueOf(body.status())) {
                case "APPROVED" -> new ChargeResult(body.paymentId(), true);
                case "DECLINED" -> new ChargeResult(body.paymentId(), false);
                default -> throw invalidResponse();
            };
        } catch (RestClientException e) {
            log.warn("PG 결제 호출 실패: orderId={}, cause={}", orderId, e.toString());
            throw DomainException.upstream("PAYMENT_GATEWAY_ERROR", "결제 서비스 호출에 실패했습니다.");
        }
    }

    @Override
    public void refund(String paymentId) {
        try {
            RefundResponse body = client.post().uri("/v1/payments/{paymentId}/refund", paymentId)
                    .retrieve()
                    .body(RefundResponse.class);
            if (body == null || !"REFUNDED".equals(body.status())) {
                throw invalidResponse();
            }
        } catch (RestClientException e) {
            log.warn("PG 환불 호출 실패: paymentId={}, cause={}", paymentId, e.toString());
            throw DomainException.upstream("PAYMENT_GATEWAY_ERROR", "결제 서비스 호출에 실패했습니다.");
        }
    }

    private static DomainException invalidResponse() {
        return DomainException.upstream("PAYMENT_GATEWAY_ERROR", "결제 서비스 응답이 올바르지 않습니다.");
    }

    private record ChargeRequest(long orderId, long amount, String cardToken) {}

    private record ChargeResponse(String paymentId, String status) {}

    private record RefundResponse(String paymentId, String status) {}
}
