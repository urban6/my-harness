package com.example.order;

import java.time.Instant;
import java.util.List;

import com.example.order.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R4 주문 조회")
class OrderQueryApiTest extends ApiTestSupport {

    @Test
    void get_returns200WithItemsAndTotals() {
        long keyboard = createProduct("키보드", 30000, 5);
        long mouse = createProduct("마우스", 15000, 5);
        long id = createOrder(List.of(item(keyboard, 2), item(mouse, 1))).getBody().get("id").asLong();

        ResponseEntity<JsonNode> res = get("/api/orders/" + id);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = res.getBody();
        assertThat(body.get("id").asLong()).isEqualTo(id);
        assertThat(body.get("status").asText()).isEqualTo("ORDERED");
        assertThat(body.get("totalPrice").asLong()).isEqualTo(75000);

        JsonNode items = body.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("productId").asLong()).isEqualTo(keyboard);
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(items.get(0).get("unitPrice").asLong()).isEqualTo(30000);
        assertThat(items.get(1).get("productId").asLong()).isEqualTo(mouse);
        assertThat(items.get(1).get("quantity").asInt()).isEqualTo(1);
        assertThat(items.get(1).get("unitPrice").asLong()).isEqualTo(15000);

        // createdAt 은 ISO-8601 문자열
        assertThat(body.get("createdAt").isTextual()).isTrue();
        assertThat(Instant.parse(body.get("createdAt").asText())).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void get_keepsUnitPriceAtOrderTime_evenIfProductPriceChanges() {
        long p = createProduct("a", 1000, 5);
        long id = createOrder(List.of(item(p, 3))).getBody().get("id").asLong();

        // 상품 수정 API는 범위 밖이므로 DB에서 직접 가격을 바꾼다.
        jdbc.update("update products set price = 9999 where id = ?", p);

        JsonNode body = get("/api/orders/" + id).getBody();
        assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(1000);
        assertThat(body.get("totalPrice").asLong()).isEqualTo(3000);
    }

    @Test
    void get_returns404_whenOrderMissing() {
        assertProblem(get("/api/orders/999999"), HttpStatus.NOT_FOUND);
    }
}
