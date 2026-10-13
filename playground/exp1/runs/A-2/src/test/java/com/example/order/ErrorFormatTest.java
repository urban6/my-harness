package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@DisplayName("R8 에러 포맷")
class ErrorFormatTest extends IntegrationTestSupport {

    @Test
    void validationErrorIsProblemDetail() {
        JsonNode body = assertProblem(post("/api/products", Map.of("name", " ", "price", 0, "stock", -1)),
                HttpStatus.BAD_REQUEST);
        assertThat(body.get("detail").asText()).contains("name", "price", "stock");
    }

    @Test
    void notFoundIsProblemDetail() {
        assertProblem(get("/api/products/12345"), HttpStatus.NOT_FOUND);
        assertProblem(get("/api/orders/12345"), HttpStatus.NOT_FOUND);
        assertProblem(post("/api/orders/12345/cancel", null), HttpStatus.NOT_FOUND);
        assertProblem(createOrder(item(12345, 1)), HttpStatus.NOT_FOUND);
    }

    @Test
    void conflictIsProblemDetail() {
        long product = createProduct("A", 1000, 0);
        assertProblem(createOrder(item(product, 1)), HttpStatus.CONFLICT);

        long other = createProduct("B", 1000, 1);
        long orderId = createOrderId(item(other, 1));
        post("/api/orders/" + orderId + "/cancel", null);
        assertProblem(post("/api/orders/" + orderId + "/cancel", null), HttpStatus.CONFLICT);
    }

    @Test
    void paginationErrorIsProblemDetail() {
        assertProblem(get("/api/orders?size=1000"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void malformedJsonIsProblemDetail() {
        assertProblem(post("/api/products", "{\"name\": \"A\", \"price\": "), HttpStatus.BAD_REQUEST);
        assertProblem(post("/api/orders", "not json"), HttpStatus.BAD_REQUEST);
        assertProblem(post("/api/orders", "{\"items\": {}}"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingBodyIsProblemDetail() {
        assertProblem(exchange(HttpMethod.POST, "/api/products", ""), HttpStatus.BAD_REQUEST);
    }

    @Test
    void problemContentTypeEvenWhenClientAcceptsJson() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = rest.exchange("/api/products/12345", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertProblem(response, HttpStatus.NOT_FOUND);
    }
}
