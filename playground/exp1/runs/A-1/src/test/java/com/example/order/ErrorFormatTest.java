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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * R8: 모든 에러 응답은 RFC 9457 Problem Details 형식이다.
 * R1~R7 테스트도 에러 응답마다 {@link #assertProblem}으로 형식을 함께 검증한다.
 */
@DisplayName("R8 에러 포맷")
class ErrorFormatTest extends ApiTestSupport {

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "{",
            "{\"name\":\"A\",\"price\":1000,\"stock\":1",
            "not json",
            "{\"name\":\"A\",\"price\":\"abc\",\"stock\":1}",
            "",
    })
    @DisplayName("상품 등록 본문 JSON 파싱 실패는 400 Problem Details")
    void malformedProductBody(String body) {
        assertProblem(post("/api/products", body), HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "{\"items\":[",
            "{\"items\":\"x\"}",
            "{\"items\":[{\"productId\":\"abc\",\"quantity\":1}]}",
            "[]",
    })
    @DisplayName("주문 생성 본문 JSON 파싱 실패는 400 Problem Details")
    void malformedOrderBody(String body) {
        assertProblem(post("/api/orders", body), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Accept: application/json 요청에도 application/problem+json으로 응답")
    void problemContentTypeEvenWhenAcceptingJson() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = rest.exchange("/api/products/12345", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertProblem(response, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("400·404·409 응답 모두 type·title·status·detail을 포함")
    void problemFieldsForEachStatus() {
        long productId = createProduct("상품", 1000, 1);

        ResponseEntity<String> badRequest = post("/api/products", "{\"name\":\" \",\"price\":1,\"stock\":1}");
        ResponseEntity<String> notFound = get("/api/orders/424242");
        ResponseEntity<String> conflict = placeOrder(items(productId, 2));

        assertProblem(badRequest, HttpStatus.BAD_REQUEST);
        assertProblem(notFound, HttpStatus.NOT_FOUND);
        assertProblem(conflict, HttpStatus.CONFLICT);

        JsonNode body = json(conflict);
        assertThat(body.get("title").asText()).isEqualTo("Conflict");
        assertThat(body.get("detail").asText()).contains(String.valueOf(productId));
    }
}
