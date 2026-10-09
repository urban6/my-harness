package com.example.order;

import java.util.List;

import com.example.order.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** R8: 모든 에러 응답이 RFC 9457 Problem Details 이다. (R1~R7 테스트도 assertProblem 으로 같은 형식을 검증한다.) */
@DisplayName("R8 에러 포맷")
class ErrorFormatTest extends ApiTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"name\":", "not json", "[1,2]", "{\"name\":\"a\",\"price\":\"abc\",\"stock\":1}"})
    void productCreate_returnsProblem_whenJsonUnparsable(String raw) {
        assertProblem(postRaw("/api/products", raw), HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"items\":", "{\"items\":{}}", "{\"items\":[{\"productId\":\"x\",\"quantity\":1}]}"})
    void orderCreate_returnsProblem_whenJsonUnparsable(String raw) {
        assertProblem(postRaw("/api/orders", raw), HttpStatus.BAD_REQUEST);
    }

    @Test
    void validationError_includesFieldErrors() {
        ResponseEntity<JsonNode> res = postRaw("/api/products", "{\"name\":\" \",\"price\":0,\"stock\":-1}");

        assertProblem(res, HttpStatus.BAD_REQUEST);
        JsonNode errors = res.getBody().get("errors");
        assertThat(errors.has("name")).isTrue();
        assertThat(errors.has("price")).isTrue();
        assertThat(errors.has("stock")).isTrue();
    }

    @Test
    void notFound_and_conflict_areProblems() {
        assertProblem(get("/api/products/1"), HttpStatus.NOT_FOUND);
        assertProblem(get("/api/orders/1"), HttpStatus.NOT_FOUND);
        assertProblem(post("/api/orders/1/cancel", null), HttpStatus.NOT_FOUND);

        long p = createProduct("a", 100, 0);
        ResponseEntity<JsonNode> conflict = createOrder(List.of(item(p, 1)));
        assertProblem(conflict, HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("detail").asText()).contains("재고");
    }

    @Test
    void nonNumericId_returnsProblem400() {
        assertProblem(get("/api/products/abc"), HttpStatus.BAD_REQUEST);
        assertProblem(get("/api/orders/abc"), HttpStatus.BAD_REQUEST);
        assertProblem(post("/api/orders/abc/cancel", null), HttpStatus.BAD_REQUEST);
    }
}
