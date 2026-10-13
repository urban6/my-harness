package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** R3(주문 생성): 정상, 검증, 404, 409, 원자성, 우선순위. */
@SuppressWarnings({"rawtypes", "unchecked"})
class OrderCreateApiTest extends AbstractIntegrationTest {

    // ---- 정상 ----

    @Test
    @DisplayName("R3: 유효한 주문이면 201 + Location(/api/orders/{id}) + status=ORDERED 본문")
    void r3_create_valid_returns201_withLocationAndBody() {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 250, 5);

        ResponseEntity<Map> res = placeOrder(item(a, 2), item(b, 3));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map body = res.getBody();
        long id = num(body, "id");
        assertThat(res.getHeaders().getLocation().toString()).endsWith("/api/orders/" + id);
        assertThat(body).containsOnlyKeys("id", "status", "totalPrice", "items", "createdAt");
        assertThat(body.get("status")).isEqualTo("ORDERED");
        assertThat(num(body, "totalPrice")).isEqualTo(2 * 1000L + 3 * 250L);
        assertThat(Instant.parse((String) body.get("createdAt"))).isNotNull();
        List<Map> items = (List<Map>) body.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsOnlyKeys("productId", "quantity", "unitPrice");
        assertThat(num(items.get(0), "productId")).isEqualTo(a);
        assertThat(num(items.get(0), "quantity")).isEqualTo(2L);
        assertThat(num(items.get(0), "unitPrice")).isEqualTo(1000L);
        assertThat(num(items.get(1), "productId")).isEqualTo(b);
        assertThat(num(items.get(1), "unitPrice")).isEqualTo(250L);
    }

    @Test
    @DisplayName("R3: 주문 성공 시 항목 수량만큼 재고가 차감된다")
    void r3_create_decreasesStock() {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 250, 5);

        createOrder(item(a, 4), item(b, 5));

        assertThat(stockOf(a)).isEqualTo(6);
        assertThat(stockOf(b)).isZero();
    }

    @Test
    @DisplayName("R3: 재고와 정확히 같은 수량 주문은 허용(경계값)")
    void r3_create_quantityEqualsStock_allowed() {
        long a = createProduct("A", 1000, 3);

        ResponseEntity<Map> res = placeOrder(item(a, 3));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockOf(a)).isZero();
    }

    // ---- 400 ----

    @Test
    @DisplayName("R3: items 누락이면 400")
    void r3_create_itemsMissing_returns400() {
        assertProblem(post("/api/orders", new HashMap<>()), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R3: items가 빈 배열이면 400")
    void r3_create_itemsEmpty_returns400() {
        assertProblem(post("/api/orders", Map.of("items", List.of())), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R3: quantity 0이면 400")
    void r3_create_quantityZero_returns400() {
        long a = createProduct("A", 1000, 10);

        assertProblem(placeOrder(item(a, 0)), HttpStatus.BAD_REQUEST);
        assertThat(stockOf(a)).isEqualTo(10);
    }

    @Test
    @DisplayName("R3: quantity 음수이면 400")
    void r3_create_quantityNegative_returns400() {
        long a = createProduct("A", 1000, 10);

        assertProblem(placeOrder(item(a, -2)), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R3: productId 또는 quantity 누락이면 400")
    void r3_create_itemFieldMissing_returns400() {
        long a = createProduct("A", 1000, 10);

        assertProblem(post("/api/orders", Map.of("items", List.of(Map.of("quantity", 1)))), HttpStatus.BAD_REQUEST);
        assertProblem(post("/api/orders", Map.of("items", List.of(Map.of("productId", a)))), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R3: 같은 productId가 중복되면 400이고 재고는 변하지 않는다")
    void r3_create_duplicateProductId_returns400() {
        long a = createProduct("A", 1000, 10);

        ResponseEntity<Map> res = placeOrder(item(a, 1), item(a, 2));

        assertProblem(res, HttpStatus.BAD_REQUEST);
        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(orderCount()).isZero();
    }

    // ---- 404 ----

    @Test
    @DisplayName("R3: 없는 상품이면 404")
    void r3_create_unknownProduct_returns404() {
        assertProblem(placeOrder(item(999999, 1)), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("R3: 일부만 없는 상품이면 404이고 존재하는 상품 재고는 차감되지 않는다")
    void r3_create_partiallyUnknownProduct_returns404_noStockChange() {
        long a = createProduct("A", 1000, 10);

        ResponseEntity<Map> res = placeOrder(item(a, 1), item(999999, 1));

        assertProblem(res, HttpStatus.NOT_FOUND);
        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(orderCount()).isZero();
    }

    // ---- 409 / 원자성 ----

    @Test
    @DisplayName("R3: 재고 부족이면 409이고 재고는 변하지 않는다")
    void r3_create_insufficientStock_returns409() {
        long a = createProduct("A", 1000, 2);

        ResponseEntity<Map> res = placeOrder(item(a, 3));

        assertProblem(res, HttpStatus.CONFLICT);
        assertThat(stockOf(a)).isEqualTo(2);
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("R3 원자성: 먼저 처리되는(id 작은) 항목은 충분하고 나중 항목만 부족해도 409, 어떤 재고도 차감되지 않는다")
    void r3_atomicity_laterItemInsufficient_noStockDeducted() {
        long a = createProduct("A", 1000, 5);
        long b = createProduct("B", 1000, 1);

        // 요청 순서는 [a, b]; 서비스는 id 오름차순으로 차감하므로 a가 먼저 차감된 뒤 b에서 실패한다.
        ResponseEntity<Map> res = placeOrder(item(a, 2), item(b, 2));

        assertProblem(res, HttpStatus.CONFLICT);
        assertThat(stockOf(a)).isEqualTo(5);
        assertThat(stockOf(b)).isEqualTo(1);
        assertThat(orderCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
    }

    @Test
    @DisplayName("R3 원자성: 요청 순서를 뒤집어도(부족 항목이 먼저 나열) 409, 재고 차감 없음")
    void r3_atomicity_reversedRequestOrder_noStockDeducted() {
        long a = createProduct("A", 1000, 5);
        long b = createProduct("B", 1000, 1);

        ResponseEntity<Map> res = placeOrder(item(b, 2), item(a, 2));

        assertProblem(res, HttpStatus.CONFLICT);
        assertThat(stockOf(a)).isEqualTo(5);
        assertThat(stockOf(b)).isEqualTo(1);
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("R3 원자성: 실패한 주문 이후에도 같은 재고로 정상 주문이 가능하다")
    void r3_atomicity_afterFailure_stockStillUsable() {
        long a = createProduct("A", 1000, 5);
        long b = createProduct("B", 1000, 1);
        placeOrder(item(a, 5), item(b, 2));

        ResponseEntity<Map> res = placeOrder(item(a, 5), item(b, 1));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockOf(a)).isZero();
        assertThat(stockOf(b)).isZero();
    }

    // ---- 우선순위 400 > 404 > 409 (설계 §5) ----

    @Test
    @DisplayName("R3 우선순위: 없는 상품 + quantity 0 이면 404가 아니라 400")
    void r3_priority_400BeatsNotFound() {
        assertProblem(placeOrder(item(999, 0)), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R3 우선순위: 중복 productId + 상품 없음 이면 400")
    void r3_priority_duplicateAndMissingProduct_returns400() {
        assertProblem(placeOrder(item(999, 1), item(999, 1)), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R3 우선순위: 400(quantity 0) + 409(재고 부족) 이면 400")
    void r3_priority_400BeatsConflict() {
        long a = createProduct("A", 1000, 1);

        assertProblem(placeOrder(item(a, 5), item(a, 0)), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R3 우선순위: 재고 0 상품 + 없는 상품 이면 409가 아니라 404, 재고 변화 없음")
    void r3_priority_404BeatsConflict() {
        long empty = createProduct("Empty", 1000, 0);
        long stocked = createProduct("Stocked", 1000, 5);

        ResponseEntity<Map> res = placeOrder(item(empty, 1), item(999999, 1), item(stocked, 1));

        assertProblem(res, HttpStatus.NOT_FOUND);
        assertThat(stockOf(empty)).isZero();
        assertThat(stockOf(stocked)).isEqualTo(5);
    }
}
