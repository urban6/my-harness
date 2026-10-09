package com.example.order;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.order.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R3 주문 생성")
class OrderCreateApiTest extends ApiTestSupport {

    @Test
    void create_returns201WithLocation_andDecreasesStock() {
        long keyboard = createProduct("키보드", 30000, 5);
        long mouse = createProduct("마우스", 15000, 3);

        ResponseEntity<JsonNode> res = createOrder(List.of(item(keyboard, 2), item(mouse, 3)));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = res.getBody();
        long id = body.get("id").asLong();
        assertThat(res.getHeaders().getLocation().getPath()).isEqualTo("/api/orders/" + id);
        assertThat(body.get("status").asText()).isEqualTo("ORDERED");
        assertThat(body.get("totalPrice").asLong()).isEqualTo(30000 * 2 + 15000 * 3);
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.hasNonNull("createdAt")).isTrue();

        assertThat(get(res.getHeaders().getLocation().getPath()).getBody()).isEqualTo(body);
        assertThat(stockOf(keyboard)).isEqualTo(3);
        assertThat(stockOf(mouse)).isZero();
    }

    @Test
    void create_returns400_whenItemsEmptyOrMissing() {
        assertProblem(createOrder(List.of()), HttpStatus.BAD_REQUEST);
        assertProblem(post("/api/orders", Map.of()), HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_returns400_whenQuantityLessThanOne() {
        long p = createProduct("a", 100, 10);

        assertProblem(createOrder(List.of(item(p, 0))), HttpStatus.BAD_REQUEST);
        assertProblem(createOrder(List.of(item(p, -1))), HttpStatus.BAD_REQUEST);
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    void create_returns400_whenProductIdOrQuantityMissing() {
        long p = createProduct("a", 100, 10);
        Map<String, Object> noQuantity = new HashMap<>(Map.of("productId", p));
        Map<String, Object> noProductId = new HashMap<>(Map.of("quantity", 1));

        assertProblem(createOrder(List.of(noQuantity)), HttpStatus.BAD_REQUEST);
        assertProblem(createOrder(List.of(noProductId)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_returns400_whenItemIsNull() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(null);

        assertProblem(createOrder(items), HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_returns400_whenDuplicateProductId() {
        long p = createProduct("a", 100, 10);

        assertProblem(createOrder(List.of(item(p, 1), item(p, 2))), HttpStatus.BAD_REQUEST);
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    void create_returns404_whenProductMissing() {
        long p = createProduct("a", 100, 10);

        assertProblem(createOrder(List.of(item(p, 1), item(999999, 1))), HttpStatus.NOT_FOUND);
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    void create_returns409_whenStockInsufficient_andDecreasesNothing() {
        long enough = createProduct("충분", 100, 10);
        long scarce = createProduct("부족", 200, 1);

        assertProblem(createOrder(List.of(item(enough, 5), item(scarce, 2))), HttpStatus.CONFLICT);

        assertThat(stockOf(enough)).isEqualTo(10);
        assertThat(stockOf(scarce)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isZero();
    }

    @Test
    void create_allowsOrderingExactlyAllStock() {
        long p = createProduct("a", 100, 3);

        assertThat(createOrder(List.of(item(p, 3))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockOf(p)).isZero();
        assertProblem(createOrder(List.of(item(p, 1))), HttpStatus.CONFLICT);
    }

    @Test
    void errorPriority_400_beats_404_and_409() {
        long scarce = createProduct("부족", 100, 0);

        // 수량 위반(400) + 없는 상품(404) + 재고 부족(409)
        assertProblem(createOrder(List.of(item(999999, 1), item(scarce, 5), item(scarce, 0))), HttpStatus.BAD_REQUEST);
    }

    @Test
    void errorPriority_404_beats_409() {
        long scarce = createProduct("부족", 100, 0);

        // 재고 부족 항목이 먼저 와도 없는 상품(404)이 우선
        assertProblem(createOrder(List.of(item(scarce, 5), item(999999, 1))), HttpStatus.NOT_FOUND);
    }
}
