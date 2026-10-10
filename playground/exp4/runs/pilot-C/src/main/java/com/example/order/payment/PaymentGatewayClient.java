package com.example.order.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
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

/**
 * 외부 PG 클라이언트 (JDK HttpClient). 트랜잭션·DB 커넥션 밖에서 호출해야 한다.
 * 연결+응답 전체에 2초 데드라인. 5xx·연결 실패·타임아웃·계약 외 응답은 모두 UNAVAILABLE.
 * cardToken 은 로그에 남기지 않는다.
 */
@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;

    public PaymentGatewayClient(PaymentGatewayProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String url = properties.url();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.timeout = properties.timeout();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    public PgResult pay(long orderId, long amount, String cardToken, String idempotencyKey) {
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
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = send(request);
            if (response == null || response.statusCode() != 200) {
                log.warn("PG payment call for order {} failed: status={}", orderId,
                        response == null ? "none" : response.statusCode());
                return PgResult.unavailable();
            }
            JsonNode node = objectMapper.readTree(response.body());
            String status = node.path("status").asText("");
            if ("APPROVED".equals(status)) {
                JsonNode paymentId = node.get("paymentId");
                if (paymentId == null || paymentId.isNull() || paymentId.asText().isBlank()) {
                    return PgResult.unavailable();
                }
                return PgResult.approved(paymentId.asText());
            }
            if ("DECLINED".equals(status)) {
                return PgResult.declined();
            }
            return PgResult.unavailable();
        } catch (Exception e) {
            log.warn("PG payment call for order {} failed: {}", orderId, e.toString());
            return PgResult.unavailable();
        }
    }

    /** @return 환불 성공 여부. 장애·계약 외 응답은 false. */
    public boolean refund(String paymentId) {
        try {
            String encoded = URLEncoder.encode(paymentId, StandardCharsets.UTF_8).replace("+", "%20");
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v1/payments/" + encoded + "/refund"))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = send(request);
            if (response == null || response.statusCode() != 200) {
                log.warn("PG refund call failed: status={}", response == null ? "none" : response.statusCode());
                return false;
            }
            return "REFUNDED".equals(objectMapper.readTree(response.body()).path("status").asText(""));
        } catch (Exception e) {
            log.warn("PG refund call failed: {}", e.toString());
            return false;
        }
    }

    /** 전체 데드라인 내에 응답을 받지 못하면 null. */
    private HttpResponse<String> send(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (ExecutionException e) {
            future.cancel(true);
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
