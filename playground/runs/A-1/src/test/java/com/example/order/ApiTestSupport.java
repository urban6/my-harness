package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class ApiTestSupport {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE order_items, orders, products RESTART IDENTITY CASCADE");
    }

    protected ResponseEntity<String> post(String path, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(jsonBody, headers), String.class);
    }

    protected ResponseEntity<String> post(String path) {
        return rest.postForEntity(path, null, String.class);
    }

    protected ResponseEntity<String> get(String path) {
        return rest.getForEntity(path, String.class);
    }

    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("Response body is not JSON: " + response.getBody(), e);
        }
    }

    protected long createProduct(String name, long price, int stock) {
        ResponseEntity<String> response = post("/api/products",
                "{\"name\":\"%s\",\"price\":%d,\"stock\":%d}".formatted(name, price, stock));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json(response).get("id").asLong();
    }

    /** productId → quantity 순서를 유지한 주문 요청 본문을 만든다. */
    protected static String orderBody(Map<Long, Integer> items) {
        return items.entrySet().stream()
                .map(e -> "{\"productId\":%d,\"quantity\":%d}".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining(",", "{\"items\":[", "]}"));
    }

    protected static Map<Long, Integer> items(Object... productIdAndQuantity) {
        Map<Long, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < productIdAndQuantity.length; i += 2) {
            items.put(((Number) productIdAndQuantity[i]).longValue(), (Integer) productIdAndQuantity[i + 1]);
        }
        return items;
    }

    protected ResponseEntity<String> placeOrder(Map<Long, Integer> items) {
        return post("/api/orders", orderBody(items));
    }

    protected long createOrder(Map<Long, Integer> items) {
        ResponseEntity<String> response = placeOrder(items);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json(response).get("id").asLong();
    }

    protected int stockOf(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    protected long countOrders() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
    }

    /** R8: RFC 9457 Problem Details 형식(application/problem+json, type·title·status·detail)인지 검증한다. */
    protected void assertProblem(ResponseEntity<String> response, HttpStatus expectedStatus) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(expectedStatus);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        JsonNode body = json(response);
        assertThat(body.path("type").isTextual()).as("type in %s", body).isTrue();
        assertThat(body.path("title").isTextual()).as("title in %s", body).isTrue();
        assertThat(body.path("status").asInt()).as("status in %s", body).isEqualTo(expectedStatus.value());
        assertThat(body.path("detail").isTextual()).as("detail in %s", body).isTrue();
        assertThat(body.path("detail").asText()).isNotBlank();
    }
}
