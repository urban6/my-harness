package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
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

/**
 * 모든 API 통합 테스트의 공통 기반. 실제 서버(RANDOM_PORT) + Testcontainers PostgreSQL.
 * 테스트 메서드에 @Transactional 을 붙이지 않는다(요청마다 독립 트랜잭션이어야 한다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
public abstract class AbstractApiTest {

    protected static final String PROBLEM_JSON = "application/problem+json";

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE order_items, orders, products RESTART IDENTITY CASCADE");
    }

    // ---------- 호출 헬퍼 ----------

    /** 원문 JSON 문자열을 그대로 POST (잘못된 JSON 등 비정상 본문 테스트용). */
    protected ResponseEntity<JsonNode> postRaw(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> post(String path, Object body) {
        try {
            return postRaw(path, objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected ResponseEntity<JsonNode> postNoBody(String path) {
        return rest.exchange(path, HttpMethod.POST, HttpEntity.EMPTY, JsonNode.class);
    }

    protected ResponseEntity<JsonNode> get(String path) {
        return rest.getForEntity(path, JsonNode.class);
    }

    /** 임의의 헤더(Accept 등)를 지정해 호출한다. body 가 null 이면 본문 없이 보낸다. */
    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String json, HttpHeaders headers) {
        return rest.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
    }

    protected static HttpHeaders jsonHeaders(MediaType accept) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(accept));
        return headers;
    }

    // ---------- 도메인 헬퍼 ----------

    protected long createProduct(String name, long price, int stock) {
        ResponseEntity<JsonNode> res = post("/api/products", Map.of("name", name, "price", price, "stock", stock));
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        return res.getBody().get("id").asLong();
    }

    protected Map<String, Object> item(long productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    protected ResponseEntity<JsonNode> createOrder(List<Map<String, Object>> items) {
        return post("/api/orders", Map.of("items", items));
    }

    protected int stockOf(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    // ---------- 단정 헬퍼 ----------

    protected static void assertProblem(ResponseEntity<JsonNode> res, int status, String typeSlug) {
        assertThat(res.getStatusCode().value()).isEqualTo(status);
        assertThat(res.getHeaders().getContentType()).isNotNull();
        assertThat(res.getHeaders().getContentType().toString()).startsWith(PROBLEM_JSON);
        JsonNode body = res.getBody();
        assertThat(body.get("type").asText()).isEqualTo("https://example.com/problems/" + typeSlug);
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("detail").asText()).isNotBlank();
    }

    /** R4 응답 형태: {id, status, totalPrice, items[{productId, quantity, unitPrice}], createdAt}. */
    protected static void assertOrderShape(JsonNode order) {
        assertThat(fieldNames(order)).containsExactlyInAnyOrder("id", "status", "totalPrice", "items", "createdAt");
        assertThat(order.get("id").isIntegralNumber()).isTrue();
        assertThat(order.get("status").asText()).isIn("ORDERED", "CANCELLED");
        assertThat(order.get("totalPrice").isIntegralNumber()).isTrue();
        assertThat(order.get("items").isArray()).isTrue();
        for (JsonNode it : order.get("items")) {
            assertThat(fieldNames(it)).containsExactlyInAnyOrder("productId", "quantity", "unitPrice");
            assertThat(it.get("unitPrice").isIntegralNumber()).isTrue();
        }
        assertThat(order.get("createdAt").isTextual()).isTrue();
        java.time.Instant.parse(order.get("createdAt").asText());
    }

    protected static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    protected static URI locationOf(ResponseEntity<?> res) {
        return res.getHeaders().getLocation();
    }
}
