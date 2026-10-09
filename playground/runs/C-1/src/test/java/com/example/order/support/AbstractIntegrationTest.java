package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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

/**
 * 통합 테스트 공통 베이스. 실제 HTTP(RANDOM_PORT) + Testcontainers PostgreSQL.
 * 테스트 메서드에 @Transactional을 붙이지 않는다(동시성 테스트 R7 전제, 01 §7.1).
 * 같은 설정을 쓰는 하위 클래스는 Spring 컨텍스트와 컨테이너를 공유한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** 상품을 등록하고 id를 반환한다. */
    @SuppressWarnings("unchecked")
    protected long createProduct(String name, long price, int stock) {
        ResponseEntity<Map> res = rest.postForEntity("/api/products",
                Map.of("name", name, "price", price, "stock", stock), Map.class);
        return ((Number) res.getBody().get("id")).longValue();
    }

    /** 주문 항목 한 개 {productId, quantity}. */
    protected static Map<String, Object> item(long productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    /** POST /api/orders 호출. */
    @SafeVarargs
    protected final ResponseEntity<Map> postOrder(Map<String, Object>... items) {
        return rest.postForEntity("/api/orders", Map.of("items", List.of(items)), Map.class);
    }

    protected ResponseEntity<Map> getProduct(long id) {
        return rest.getForEntity("/api/products/{id}", Map.class, id);
    }

    // ---- test-writer 확장 헬퍼 ----

    /** 임의의 JSON 문자열 본문을 그대로 POST한다(깨진 JSON, null 필드 등 Map으로 만들 수 없는 요청용). */
    protected ResponseEntity<Map> postRaw(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), Map.class);
    }

    protected ResponseEntity<Map> getOrder(long id) {
        return rest.getForEntity("/api/orders/{id}", Map.class, id);
    }

    protected ResponseEntity<Map> cancelOrder(long id) {
        return rest.postForEntity("/api/orders/{id}/cancel", null, Map.class, id);
    }

    protected static long idOf(ResponseEntity<Map> response) {
        return ((Number) response.getBody().get("id")).longValue();
    }

    protected int stockOf(long productId) {
        return ((Number) getProduct(productId).getBody().get("stock")).intValue();
    }

    /** 테스트 데이터 격리: FK 순서(order_items -> orders -> products)로 전부 비운다. */
    protected void cleanDatabase() {
        jdbcTemplate.update("delete from order_items");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from products");
    }

    /** R8: RFC 9457 형태(application/problem+json, type/title/status/detail, status 일치)를 검증한다. */
    protected static void assertProblem(ResponseEntity<Map> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).isEqualTo(expected);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("problem+json");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        assertThat(body).containsKeys("type", "title", "status", "detail");
        assertThat(((Number) body.get("status")).intValue()).isEqualTo(expected.value());
        assertThat((String) body.get("type")).isNotBlank();
        assertThat((String) body.get("title")).isNotBlank();
        assertThat((String) body.get("detail")).isNotBlank();
    }
}
