package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 스모크 테스트. 컨텍스트 기동(Flyway V1 + ddl-auto=validate) 및 상품 등록 -> 주문 생성 -> 조회 정상 경로.
 * 전체 커버리지는 Phase 3(test-writer)에서 확장한다.
 */
class OrderSmokeTest extends AbstractIntegrationTest {

    @Test
    void context_starts_and_flyway_applied_with_schema_validated() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true AND version = '1'",
                Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createProduct_then_createOrder_then_get_happyPath() {
        ResponseEntity<Map> product = rest.postForEntity("/api/products",
                Map.of("name", "키보드", "price", 30000, "stock", 10), Map.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number productId = (Number) product.getBody().get("id");
        assertThat(product.getHeaders().getLocation().toString()).endsWith("/api/products/" + productId);

        ResponseEntity<Map> order = rest.postForEntity("/api/orders",
                Map.of("items", List.of(Map.of("productId", productId, "quantity", 3))), Map.class);
        assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number orderId = (Number) order.getBody().get("id");
        assertThat(order.getHeaders().getLocation().toString()).endsWith("/api/orders/" + orderId);
        assertThat(order.getBody().get("status")).isEqualTo("ORDERED");
        assertThat(((Number) order.getBody().get("totalPrice")).longValue()).isEqualTo(90000L);

        ResponseEntity<Map> fetched = rest.getForEntity("/api/orders/" + orderId, Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(order.getBody());

        ResponseEntity<Map> after = rest.getForEntity("/api/products/" + productId, Map.class);
        assertThat(((Number) after.getBody().get("stock")).intValue()).isEqualTo(7);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listOrders_hugePage_returns200WithEmptyContent() {
        ResponseEntity<Map> res = rest.getForEntity("/api/orders?page=2147483647&size=100", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Object>) res.getBody().get("content")).isEmpty();
        assertThat(((Number) res.getBody().get("page")).intValue()).isEqualTo(Integer.MAX_VALUE);
    }
}
