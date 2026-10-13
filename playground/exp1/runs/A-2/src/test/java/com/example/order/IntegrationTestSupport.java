package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestSupport {

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

    protected ResponseEntity<String> post(String path, Object body) {
        String json;
        try {
            json = body instanceof String s ? s : objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return exchange(HttpMethod.POST, path, json);
    }

    protected ResponseEntity<String> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    protected ResponseEntity<String> exchange(HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        if (json != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(json, headers), String.class);
    }

    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response is not JSON: " + response.getBody(), e);
        }
    }

    protected long createProduct(String name, long price, int stock) {
        ResponseEntity<String> response = post("/api/products", Map.of("name", name, "price", price, "stock", stock));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json(response).get("id").asLong();
    }

    protected int stockOf(long productId) {
        return json(get("/api/products/" + productId)).get("stock").asInt();
    }

    protected static Map<String, Object> item(long productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    protected static Map<String, Object> orderOf(Map<?, ?>... items) {
        return Map.of("items", List.of(items));
    }

    protected ResponseEntity<String> createOrder(Map<?, ?>... items) {
        return post("/api/orders", orderOf(items));
    }

    protected long createOrderId(Map<?, ?>... items) {
        ResponseEntity<String> response = createOrder(items);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json(response).get("id").asLong();
    }

    /** Asserts the response is an RFC 9457 Problem Details document with the given status (R8). */
    protected JsonNode assertProblem(ResponseEntity<String> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("Content-Type of %s", response.getHeaders().getContentType())
                .isTrue();
        JsonNode body = json(response);
        assertThat(body.hasNonNull("type")).as("type in %s", body).isTrue();
        assertThat(body.hasNonNull("title")).as("title in %s", body).isTrue();
        assertThat(body.get("status").asInt()).isEqualTo(status.value());
        assertThat(body.hasNonNull("detail")).as("detail in %s", body).isTrue();
        return body;
    }
}
