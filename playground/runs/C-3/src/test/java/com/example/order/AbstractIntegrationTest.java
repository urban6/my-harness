package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 통합 테스트 공통 베이스. 모든 통합 테스트 클래스가 하나의 PostgreSQL 컨테이너와 하나의 스프링 컨텍스트를 공유한다.
 * 공유 DB이므로 매 테스트 전에 테이블을 TRUNCATE 하여 테스트 간 데이터 의존을 제거한다.
 * (테스트 클래스는 순차 실행된다. 병렬 실행 설정을 추가하면 이 격리 전략이 깨진다.)
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE order_items, orders, products RESTART IDENTITY CASCADE");
    }

    // ---- 요청 헬퍼 ----

    @SuppressWarnings("rawtypes")
    protected ResponseEntity<Map> post(String url, Object body) {
        return rest.postForEntity(url, body, Map.class);
    }

    /** 본문을 가공 없이 JSON 문자열로 전송한다 (깨진 JSON 등). */
    @SuppressWarnings("rawtypes")
    protected ResponseEntity<Map> postRaw(String url, String rawBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url, new HttpEntity<>(rawBody, headers), Map.class);
    }

    @SuppressWarnings("rawtypes")
    protected ResponseEntity<Map> get(String url) {
        return rest.getForEntity(url, Map.class);
    }

    // ---- 도메인 헬퍼 ----

    @SuppressWarnings("rawtypes")
    protected long createProduct(String name, long price, int stock) {
        ResponseEntity<Map> res = post("/api/products", Map.of("name", name, "price", price, "stock", stock));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return num(res.getBody(), "id");
    }

    protected static Map<String, Object> item(long productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    @SafeVarargs
    @SuppressWarnings("rawtypes")
    protected final ResponseEntity<Map> placeOrder(Map<String, Object>... items) {
        return post("/api/orders", Map.of("items", List.of(items)));
    }

    /** 주문을 생성하고 201을 확인한 뒤 응답 본문을 돌려준다. */
    @SafeVarargs
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected final Map<String, Object> createOrder(Map<String, Object>... items) {
        ResponseEntity<Map> res = placeOrder(items);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    @SuppressWarnings("rawtypes")
    protected int stockOf(long productId) {
        ResponseEntity<Map> res = get("/api/products/" + productId);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (int) num(res.getBody(), "stock");
    }

    protected long orderCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
        return count == null ? 0 : count;
    }

    protected static long num(Map<?, ?> body, String key) {
        Object value = body.get(key);
        assertThat(value).as("field '%s'", key).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    // ---- 공통 단언 ----

    /** R8: RFC 9457 형태와 HTTP 상태 일치를 검증한다. */
    @SuppressWarnings("rawtypes")
    protected static void assertProblem(ResponseEntity<Map> res, HttpStatus expected) {
        assertThat(res.getStatusCode()).isEqualTo(expected);
        MediaType contentType = res.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        Map body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("type", "title", "status", "detail");
        assertThat(body.get("type")).isInstanceOf(String.class);
        assertThat((String) body.get("title")).isNotBlank();
        assertThat((String) body.get("detail")).isNotBlank();
        assertThat(((Number) body.get("status")).intValue()).isEqualTo(expected.value());
    }
}
