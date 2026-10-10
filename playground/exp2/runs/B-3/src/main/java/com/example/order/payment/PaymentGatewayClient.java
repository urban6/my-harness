package com.example.order.payment;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import java.net.http.HttpClient;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 외부 PG 클라이언트. 5xx·연결 실패·타임아웃(기본 2초)·해석할 수 없는 응답은 모두 PG 장애로 본다.
 */
@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);

    private final RestClient restClient;

    public PaymentGatewayClient(RestClient.Builder builder, PaymentGatewayProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = builder.clone()
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .build();
    }

    public PaymentResult pay(String idempotencyKey, long orderId, long amount, String cardToken) {
        PgResponse response = call(() -> restClient.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PgPaymentRequest(orderId, amount, cardToken))
                .retrieve()
                .body(PgResponse.class));
        if ("APPROVED".equals(response.status())) {
            return new PaymentResult(response.paymentId(), true);
        }
        if ("DECLINED".equals(response.status())) {
            return new PaymentResult(response.paymentId(), false);
        }
        throw unavailable("알 수 없는 결제 상태: " + response.status());
    }

    public void refund(String paymentId) {
        PgResponse response = call(() -> restClient.post()
                .uri("/v1/payments/{paymentId}/refund", paymentId)
                .retrieve()
                .body(PgResponse.class));
        if (!"REFUNDED".equals(response.status())) {
            throw unavailable("알 수 없는 환불 상태: " + response.status());
        }
    }

    private PgResponse call(Supplier<PgResponse> request) {
        PgResponse response;
        try {
            response = request.get();
        } catch (RestClientException e) {
            throw unavailable(e.getMessage());
        }
        if (response == null) {
            throw unavailable("빈 응답");
        }
        return response;
    }

    private static BusinessException unavailable(String reason) {
        log.warn("Payment gateway unavailable: {}", reason);
        return new BusinessException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "결제 대행사를 사용할 수 없습니다.");
    }

    record PgPaymentRequest(long orderId, long amount, String cardToken) {
    }

    record PgResponse(String paymentId, String status) {
    }
}
