package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** R4(주문 조회), R5(주문 취소). */
@SuppressWarnings({"rawtypes", "unchecked"})
class OrderQueryCancelApiTest extends AbstractIntegrationTest {

    // ================= R4 =================

    @Test
    @DisplayName("R4: 200 + {id,status,totalPrice,items[{productId,quantity,unitPrice}],createdAt} 형태")
    void r4_get_returns200WithShape() {
        long a = createProduct("A", 1200, 10);
        Map<String, Object> created = createOrder(item(a, 2));

        ResponseEntity<Map> res = get("/api/orders/" + num(created, "id"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsOnlyKeys("id", "status", "totalPrice", "items", "createdAt");
        assertThat(res.getBody().get("status")).isEqualTo("ORDERED");
        List<Map> items = (List<Map>) res.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsOnlyKeys("productId", "quantity", "unitPrice");
    }

    @Test
    @DisplayName("R4: totalPrice는 Σ(unitPrice x quantity)이다")
    void r4_get_totalPriceIsSumOfUnitPriceTimesQuantity() {
        long a = createProduct("A", 1200, 10);
        long b = createProduct("B", 330, 10);
        long c = createProduct("C", 7, 10);
        Map<String, Object> created = createOrder(item(a, 2), item(b, 3), item(c, 10));

        Map body = get("/api/orders/" + num(created, "id")).getBody();

        long expected = 0;
        for (Map it : (List<Map>) body.get("items")) {
            expected += num(it, "unitPrice") * num(it, "quantity");
        }
        assertThat(num(body, "totalPrice")).isEqualTo(expected).isEqualTo(1200 * 2 + 330 * 3 + 7 * 10L);
    }

    @Test
    @DisplayName("R4: unitPrice는 주문 시점 가격이며 이후 상품 가격이 바뀌어도 변하지 않는다")
    void r4_get_unitPriceIsPriceAtOrderTime() {
        long a = createProduct("A", 1000, 10);
        Map<String, Object> created = createOrder(item(a, 2));

        jdbc.update("UPDATE products SET price = 5000 WHERE id = ?", a);

        Map body = get("/api/orders/" + num(created, "id")).getBody();
        Map firstItem = ((List<Map>) body.get("items")).get(0);
        assertThat(num(firstItem, "unitPrice")).isEqualTo(1000L);
        assertThat(num(body, "totalPrice")).isEqualTo(2000L);
    }

    @Test
    @DisplayName("R4: createdAt은 ISO-8601 문자열로 파싱 가능하다")
    void r4_get_createdAtIsIso8601String() {
        long a = createProduct("A", 1000, 10);
        Map<String, Object> created = createOrder(item(a, 1));

        Object createdAt = get("/api/orders/" + num(created, "id")).getBody().get("createdAt");

        assertThat(createdAt).isInstanceOf(String.class);
        assertThat(Instant.parse((String) createdAt)).isBefore(Instant.now().plusSeconds(5));
    }

    @Test
    @DisplayName("R4: 생성 응답과 조회 응답의 본문(createdAt 포함)이 동일하다")
    void r4_get_matchesCreateResponse_includingCreatedAt() {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 500, 10);
        Map<String, Object> created = createOrder(item(a, 1), item(b, 2));

        Map fetched = get("/api/orders/" + num(created, "id")).getBody();

        assertThat(fetched.get("createdAt")).isEqualTo(created.get("createdAt"));
        assertThat(fetched).isEqualTo(created);
    }

    @Test
    @DisplayName("R4: items는 요청에 넣은 순서를 유지한다")
    void r4_get_itemsKeepRequestOrder() {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 500, 10);
        Map<String, Object> created = createOrder(item(b, 1), item(a, 1));

        List<Map> items = (List<Map>) get("/api/orders/" + num(created, "id")).getBody().get("items");

        assertThat(items).extracting(m -> num(m, "productId")).containsExactly(b, a);
    }

    @Test
    @DisplayName("R4: 없는 주문 id는 404")
    void r4_get_unknownId_returns404() {
        assertProblem(get("/api/orders/999999"), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("R4: 숫자가 아닌 id는 400")
    void r4_get_nonNumericId_returns400() {
        assertProblem(get("/api/orders/abc"), HttpStatus.BAD_REQUEST);
    }

    // ================= R5 =================

    @Test
    @DisplayName("R5: 취소하면 200 + status=CANCELLED, 나머지 필드는 생성 시와 같다")
    void r5_cancel_returns200_cancelled() {
        long a = createProduct("A", 1000, 10);
        Map<String, Object> created = createOrder(item(a, 2));

        ResponseEntity<Map> res = post("/api/orders/" + num(created, "id") + "/cancel", null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsOnlyKeys("id", "status", "totalPrice", "items", "createdAt");
        assertThat(res.getBody().get("status")).isEqualTo("CANCELLED");
        assertThat(res.getBody().get("id")).isEqualTo(created.get("id"));
        assertThat(res.getBody().get("totalPrice")).isEqualTo(created.get("totalPrice"));
        assertThat(res.getBody().get("items")).isEqualTo(created.get("items"));
        assertThat(res.getBody().get("createdAt")).isEqualTo(created.get("createdAt"));
    }

    @Test
    @DisplayName("R5: 취소하면 주문 수량만큼 모든 항목의 재고가 복원된다")
    void r5_cancel_restoresStock() {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 500, 4);
        Map<String, Object> created = createOrder(item(a, 3), item(b, 4));
        assertThat(stockOf(a)).isEqualTo(7);
        assertThat(stockOf(b)).isZero();

        post("/api/orders/" + num(created, "id") + "/cancel", null);

        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(stockOf(b)).isEqualTo(4);
    }

    @Test
    @DisplayName("R5: 취소 후 조회하면 status=CANCELLED가 유지된다")
    void r5_cancel_thenGet_showsCancelled() {
        long a = createProduct("A", 1000, 10);
        Map<String, Object> created = createOrder(item(a, 1));
        long id = num(created, "id");

        post("/api/orders/" + id + "/cancel", null);

        assertThat(get("/api/orders/" + id).getBody().get("status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("R5: 이미 취소된 주문을 다시 취소하면 409이고 재고가 두 번 복원되지 않는다")
    void r5_cancel_twice_returns409_noDoubleRestore() {
        long a = createProduct("A", 1000, 10);
        Map<String, Object> created = createOrder(item(a, 3));
        String url = "/api/orders/" + num(created, "id") + "/cancel";
        post(url, null);

        ResponseEntity<Map> second = post(url, null);

        assertProblem(second, HttpStatus.CONFLICT);
        assertThat(stockOf(a)).isEqualTo(10);
    }

    @Test
    @DisplayName("R5: 없는 주문 취소는 404")
    void r5_cancel_unknownOrder_returns404() {
        assertProblem(post("/api/orders/999999/cancel", null), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("R5: 숫자가 아닌 id 취소는 400")
    void r5_cancel_nonNumericId_returns400() {
        assertProblem(post("/api/orders/abc/cancel", null), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R5: 취소로 복원된 재고로 다시 주문할 수 있다")
    void r5_cancel_thenReorderWithRestoredStock() {
        long a = createProduct("A", 1000, 2);
        Map<String, Object> created = createOrder(item(a, 2));
        assertProblem(placeOrder(item(a, 1)), HttpStatus.CONFLICT);

        post("/api/orders/" + num(created, "id") + "/cancel", null);

        assertThat(placeOrder(item(a, 2)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
