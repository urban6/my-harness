package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
        jdbc.execute("TRUNCATE TABLE order_items, orders, products RESTART IDENTITY CASCADE");
    }

    protected ResponseEntity<String> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    protected ResponseEntity<String> post(String path) {
        return post(path, null);
    }

    protected ResponseEntity<String> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        return json(response).get("id").asLong();
    }

    protected ResponseEntity<String> createOrder(long... productIdAndQuantityPairs) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < productIdAndQuantityPairs.length; i += 2) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"productId\":%d,\"quantity\":%d}"
                    .formatted(productIdAndQuantityPairs[i], productIdAndQuantityPairs[i + 1]));
        }
        return post("/api/orders", "{\"items\":[" + items + "]}");
    }

    protected long createOrderId(long... productIdAndQuantityPairs) {
        ResponseEntity<String> response = createOrder(productIdAndQuantityPairs);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        return json(response).get("id").asLong();
    }

    protected int stockOf(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    /** R8: every error is an RFC 9457 problem with type, title, status and detail. */
    protected JsonNode assertProblem(ResponseEntity<String> response, int expectedStatus) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(expectedStatus);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        JsonNode body = json(response);
        assertThat(body.hasNonNull("type")).as("type in %s", body).isTrue();
        assertThat(body.hasNonNull("title")).as("title in %s", body).isTrue();
        assertThat(body.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(body.path("detail").isTextual()).as("detail in %s", body).isTrue();
        assertThat(body.path("detail").asText()).isNotBlank();
        return body;
    }
}
