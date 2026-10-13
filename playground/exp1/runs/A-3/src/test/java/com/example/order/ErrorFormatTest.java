package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@DisplayName("R8 에러 포맷 (RFC 9457 Problem Details)")
class ErrorFormatTest extends IntegrationTestSupport {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/products", "/api/orders"})
    @DisplayName("요청 본문 JSON 파싱 실패는 400 problem+json")
    void malformedJson(String path) {
        assertProblem(post(path, "{\"name\": \"broken\""), 400);
        assertProblem(post(path, "not json at all"), 400);
        assertProblem(post(path, "[1, 2, 3]"), 400);
    }

    @Test
    @DisplayName("요청 본문이 없으면 400 problem+json")
    void missingBody() {
        assertProblem(post("/api/products", ""), 400);
        assertProblem(post("/api/orders", ""), 400);
    }

    @Test
    @DisplayName("필드 타입이 맞지 않으면 400 problem+json")
    void typeMismatchInBody() {
        assertProblem(post("/api/products", "{\"name\":\"A\",\"price\":\"abc\",\"stock\":1}"), 400);
        assertProblem(post("/api/orders", "{\"items\":{\"productId\":1,\"quantity\":1}}"), 400);
    }

    @Test
    @DisplayName("R1~R7의 400·404·409 응답은 모두 type·title·status·detail을 갖는 problem+json이다")
    void allDefinedErrorsUseProblemDetails() {
        long product = createProduct("A", 1000, 1);
        long orderId = createOrderId(product, 1);
        assertThat(post("/api/orders/" + orderId + "/cancel").getStatusCode().value()).isEqualTo(200);

        JsonNode badRequest = assertProblem(post("/api/products", "{\"name\":\" \",\"price\":0,\"stock\":-1}"), 400);
        assertProblem(get("/api/products/424242"), 404);
        assertProblem(post("/api/orders", "{\"items\":[]}"), 400);
        assertProblem(createOrder(424242, 1), 404);
        assertProblem(createOrder(product, 2), 409);
        assertProblem(get("/api/orders/424242"), 404);
        assertProblem(post("/api/orders/424242/cancel"), 404);
        JsonNode conflict = assertProblem(post("/api/orders/" + orderId + "/cancel"), 409);
        assertProblem(get("/api/orders?size=0"), 400);

        assertThat(badRequest.get("title").asText()).isEqualTo("Bad Request");
        assertThat(badRequest.get("detail").asText()).contains("name", "price", "stock");
        assertThat(conflict.get("title").asText()).isEqualTo("Conflict");
    }

    @Test
    @DisplayName("Accept 헤더가 없어도 problem+json으로 응답한다")
    void problemJsonWithoutAcceptHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/products", HttpMethod.POST,
                new HttpEntity<>("{oops", headers), String.class);

        assertProblem(response, 400);
    }

    @Test
    @DisplayName("Accept: application/problem+json 요청에도 problem+json으로 응답한다")
    void problemJsonWithProblemAcceptHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_PROBLEM_JSON));
        ResponseEntity<String> response = rest.exchange("/api/orders/424242", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertProblem(response, 404);
    }
}
