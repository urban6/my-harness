package com.example.order.api;

import static com.example.order.api.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiClient;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@DisplayName("REQ-15 RFC 9457 Problem Details contract")
class ProblemDetailsTest extends IntegrationTestBase {

    @Test
    @DisplayName("REQ-15 409: application/problem+json 이고 type/title/status/detail/instance/code + 확장 필드를 모두 가진다")
    void conflictHasAllStandardAndExtensionFields() {
        long p = api.newProduct(1000, 1);

        ResponseEntity<JsonNode> res = api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), null,
                ApiClient.item(p, 5));

        assertProblem(res, 409, "insufficient-stock");
        JsonNode body = res.getBody();
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("instance").asText()).isEqualTo("/api/orders");
        assertThat(body.get("productId").asLong()).isEqualTo(p);
        assertThat(body.get("requested").asInt()).isEqualTo(5);
        assertThat(body.get("available").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-15 instance 는 요청 경로이며 쿼리스트링을 포함하지 않는다")
    void instanceExcludesQueryString() {
        ResponseEntity<JsonNode> res = api.get("/api/orders?size=0");

        assertProblem(res, 400, "invalid-parameter");
        assertThat(res.getBody().get("instance").asText()).isEqualTo("/api/orders");
    }

    @Test
    @DisplayName("REQ-15 검증 실패 400: errors[] 는 {field, message} 를 가진다")
    void validationErrorsHaveFieldAndMessage() {
        ResponseEntity<JsonNode> res = api.createProduct("", -5, -1);

        assertProblem(res, 400, "validation-failed");
        JsonNode errors = res.getBody().get("errors");
        assertThat(errors.size()).isEqualTo(3);
        errors.forEach(e -> {
            assertThat(e.get("field").asText()).isNotBlank();
            assertThat(e.get("message").asText()).isNotBlank();
        });
        assertThat(ApiClient.fieldsOf(res.getBody())).containsExactlyInAnyOrder("name", "price", "stock");
    }

    @Test
    @DisplayName("REQ-15 404 (미존재 리소스) / 매핑되지 않은 경로 -> resource-not-found")
    void unmappedPathIsResourceNotFound() {
        ResponseEntity<JsonNode> res = api.get("/api/does-not-exist");

        assertProblem(res, 404, "resource-not-found");
        assertThat(res.getBody().get("instance").asText()).isEqualTo("/api/does-not-exist");
    }

    @Test
    @DisplayName("REQ-15 405 -> method-not-allowed (problem+json)")
    void wrongMethodIsMethodNotAllowed() {
        ResponseEntity<JsonNode> res = api.exchange(HttpMethod.DELETE, "/api/products/1", null, null);

        assertProblem(res, 405, "method-not-allowed");
    }

    @Test
    @DisplayName("REQ-15 415 -> unsupported-media-type (problem+json)")
    void wrongContentTypeIsUnsupportedMediaType() {
        ResponseEntity<JsonNode> res = api.exchange(HttpMethod.POST, "/api/products", "name=x", MediaType.TEXT_PLAIN);

        assertProblem(res, 415, "unsupported-media-type");
    }

    @Test
    @DisplayName("REQ-15 필수 헤더 누락 400 -> missing-required-header (header 확장)")
    void missingHeaderIsProblem() {
        ResponseEntity<JsonNode> res = api.post("/api/orders/1/pay", Map.of("cardToken", "t"));

        assertProblem(res, 400, "missing-required-header");
        assertThat(res.getBody().get("header").asText()).isEqualTo("Idempotency-Key");
    }

    @Test
    @DisplayName("REQ-15 PG 장애 502/504 도 problem+json (type/code/retryable)")
    void gatewayFailuresAreProblems() {
        long orderId = api.orderOk(api.newProduct(1000, 5), 1).get("id").asLong();
        PG.onApprove(c -> com.example.order.support.PgStub.Reply.json(500, "{}"));

        ResponseEntity<JsonNode> res = api.pay(orderId, ApiClient.uniq("pay"), "tok_ok");

        assertProblem(res, 502, "payment-gateway-error");
        assertThat(res.getBody().get("retryable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("REQ-15 예기치 못한 예외 -> 500 internal-error problem+json, 내부 정보(예외 메시지/스택)는 노출하지 않는다")
    void unexpectedExceptionIs500WithoutLeakingInternals() {
        long p = api.newProduct(1000, 5);
        long orderId = api.orderOk(p, 1).get("id").asLong();
        api.payOk(orderId);
        // corrupt the data: PAID order without a payment row makes cancel hit an invariant violation
        jdbc.update("delete from payments where order_id = ?", orderId);

        ResponseEntity<JsonNode> res = api.cancel(orderId);

        assertProblem(res, 500, "internal-error");
        String text = res.getBody().toString();
        assertThat(text).doesNotContain("IllegalStateException").doesNotContain("has no payment")
                .doesNotContain("org.springframework").doesNotContain("at com.example");
        assertThat(orderStatusInDb(orderId)).isEqualTo("PAID");
        assertThat(api.product(p).get("stock").asInt()).isEqualTo(4);
    }
}
