package com.example.order.gateway;

import com.example.order.config.PaymentGatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * 외부 PG 클라이언트. java.net.http.HttpClient 의 sendAsync 결과를 get(timeout) 으로 기다려
 * 연결 + 송신 + 응답 수신 전체를 하나의 데드라인(기본 2초)에 묶는다. 자동 재시도·리다이렉트 없음.
 * 모든 장애(5xx, 4xx, 연결 실패, 타임아웃, 해석 불가 본문)는 예외 없이 UNAVAILABLE 결과로 반환한다.
 * cardToken 은 로그에 남기지 않는다.
 */
@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);

    public sealed interface PayResult permits Approved, Declined, Unavailable {
    }

    public record Approved(String paymentId) implements PayResult {
    }

    public record Declined() implements PayResult {
    }

    public record Unavailable() implements PayResult {
    }

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;
    /** 앱 기본(strict) ObjectMapper 와 별도의 관대한 인스턴스: paymentId 가 숫자여도 문자열로 수용. */
    private final ObjectMapper lenient = new ObjectMapper();

    public PaymentGatewayClient(PaymentGatewayProperties props) {
        String url = props.url();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.timeout = props.timeout();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
    }

    public PayResult charge(long orderId, long amount, String cardToken, String idempotencyKey) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", orderId);
            payload.put("amount", amount);
            payload.put("cardToken", cardToken);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payments"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(lenient.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = execute(request);
            if (response == null || !is2xx(response.statusCode())) {
                return new Unavailable();
            }
            JsonNode node = lenient.readTree(response.body());
            JsonNode status = node == null ? null : node.get("status");
            if (status == null || !status.isTextual()) {
                return new Unavailable();
            }
            if ("DECLINED".equals(status.asText())) {
                return new Declined();
            }
            if ("APPROVED".equals(status.asText())) {
                JsonNode paymentId = node.get("paymentId");
                if (paymentId == null || paymentId.isNull() || paymentId.isContainerNode()
                        || paymentId.asText().isBlank() || paymentId.asText().length() > 100) {
                    return new Unavailable();
                }
                return new Approved(paymentId.asText());
            }
            return new Unavailable();
        } catch (Exception e) {
            log.warn("PG charge failed: {}", e.getClass().getSimpleName());
            return new Unavailable();
        }
    }

    /** 환불 성공이면 true, 그 밖(장애·해석 불가)은 false. */
    public boolean refund(String paymentId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v1/payments/" + UriUtils.encodePathSegment(paymentId, StandardCharsets.UTF_8) + "/refund"))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = execute(request);
            if (response == null || !is2xx(response.statusCode())) {
                return false;
            }
            JsonNode node = lenient.readTree(response.body());
            JsonNode status = node == null ? null : node.get("status");
            return status != null && status.isTextual() && "REFUNDED".equals(status.asText());
        } catch (Exception e) {
            log.warn("PG refund failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private HttpResponse<String> execute(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    private static boolean is2xx(int status) {
        return status >= 200 && status < 300;
    }
}
