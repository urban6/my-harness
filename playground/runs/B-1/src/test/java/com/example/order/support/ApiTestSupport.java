package com.example.order.support;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 PostgreSQL(Testcontainers)과 실제 HTTP 서버로 API를 호출하는 통합 테스트 기반. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class ApiTestSupport {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE order_items, orders, products RESTART IDENTITY CASCADE");
    }

    protected ResponseEntity<JsonNode> get(String url) {
        return rest.getForEntity(url, JsonNode.class);
    }

    protected ResponseEntity<JsonNode> post(String url, Object body) {
        return rest.postForEntity(url, body, JsonNode.class);
    }

    /** 문자열을 그대로 JSON 본문으로 보낸다(파싱 실패 케이스용). */
    protected ResponseEntity<JsonNode> postRaw(String url, String rawJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(rawJson, headers), JsonNode.class);
    }

    protected long createProduct(String name, long price, int stock) {
        ResponseEntity<JsonNode> res = post("/api/products", Map.of("name", name, "price", price, "stock", stock));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().get("id").asLong();
    }

    protected ResponseEntity<JsonNode> createOrder(List<Map<String, Object>> items) {
        return post("/api/orders", Map.of("items", items));
    }

    protected static Map<String, Object> item(long productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    protected long stockOf(long productId) {
        return get("/api/products/" + productId).getBody().get("stock").asLong();
    }

    /** R8: RFC 9457 Problem Details 형식과 상태 코드를 검증한다. */
    protected static void assertProblem(ResponseEntity<JsonNode> res, HttpStatus status) {
        assertThat(res.getStatusCode()).isEqualTo(status);
        assertThat(res.getHeaders().getContentType()).isNotNull();
        assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        JsonNode body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.hasNonNull("type")).isTrue();
        assertThat(body.hasNonNull("title")).isTrue();
        assertThat(body.get("status").asInt()).isEqualTo(status.value());
        assertThat(body.hasNonNull("detail")).isTrue();
    }
}
