package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.order.support.AbstractIntegrationTest;

/** R4 주문 조회, R5 주문 취소. */
class OrderRetrieveCancelApiTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------ R4

    @Test
    @DisplayName("R4: 주문 조회는 200 + {id,status,totalPrice,items[{productId,quantity,unitPrice}],createdAt}")
    void r4_getOrder_returns200WithShape() {
        long a = createProduct("A", 1200, 10);
        long b = createProduct("B", 300, 10);
        long orderId = idOf(postOrder(item(a, 2), item(b, 5)));

        ResponseEntity<Map> res = getOrder(orderId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsOnlyKeys("id", "status", "totalPrice", "items", "createdAt")
                .containsEntry("id", (int) orderId)
                .containsEntry("status", "ORDERED");
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.getBody().get("items");
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(i -> assertThat(i).containsOnlyKeys("productId", "quantity", "unitPrice"));
    }

    @Test
    @DisplayName("R4: totalPrice는 Σ(unitPrice x quantity)")
    void r4_getOrder_totalPriceIsSumOfUnitPriceTimesQuantity() {
        long a = createProduct("A", 1200, 10);
        long b = createProduct("B", 300, 10);
        long orderId = idOf(postOrder(item(a, 2), item(b, 5)));

        Map<String, Object> body = getOrder(orderId).getBody();

        long sum = ((List<Map<String, Object>>) body.get("items")).stream()
                .mapToLong(i -> ((Number) i.get("unitPrice")).longValue() * ((Number) i.get("quantity")).longValue())
                .sum();
        assertThat(sum).isEqualTo(1200 * 2 + 300 * 5);
        assertThat(((Number) body.get("totalPrice")).longValue()).isEqualTo(sum);
    }

    @Test
    @DisplayName("R4: unitPrice는 주문 시점 가격이라 이후 상품 가격이 바뀌어도 유지된다")
    void r4_getOrder_unitPriceIsSnapshotAtOrderTime() {
        long a = createProduct("A", 1000, 10);
        long orderId = idOf(postOrder(item(a, 2)));

        jdbcTemplate.update("update products set price = 9999 where id = ?", a);
        Map<String, Object> body = getOrder(orderId).getBody();

        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertThat(items.get(0)).containsEntry("unitPrice", 1000);
        assertThat(body).containsEntry("totalPrice", 2000);
    }

    @Test
    @DisplayName("R4: createdAt은 ISO-8601로 파싱 가능하고 생성 응답과 조회 응답이 같다")
    void r4_getOrder_createdAtIsIso8601_andMatchesCreateResponse() {
        long a = createProduct("A", 1000, 10);
        ResponseEntity<Map> created = postOrder(item(a, 1));
        Instant before = Instant.now();

        Map<String, Object> body = getOrder(idOf(created)).getBody();

        Instant createdAt = Instant.parse((String) body.get("createdAt"));
        assertThat(createdAt).isBeforeOrEqualTo(before);
        assertThat(body.get("createdAt")).isEqualTo(created.getBody().get("createdAt"));
    }

    @Test
    @DisplayName("R4: 없는 주문 id는 404")
    void r4_getOrder_unknownId_returns404() {
        assertProblem(getOrder(999_999L), HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------ R5

    @Test
    @DisplayName("R5: 취소하면 200 + status=CANCELLED + R4와 같은 형태")
    void r5_cancelOrder_returns200AndCancelledStatus() {
        long a = createProduct("A", 1000, 10);
        ResponseEntity<Map> created = postOrder(item(a, 3));
        long orderId = idOf(created);

        ResponseEntity<Map> res = cancelOrder(orderId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsOnlyKeys("id", "status", "totalPrice", "items", "createdAt")
                .containsEntry("id", (int) orderId)
                .containsEntry("status", "CANCELLED")
                .containsEntry("totalPrice", 3000)
                .containsEntry("createdAt", created.getBody().get("createdAt"));
        assertThat(getOrder(orderId).getBody()).containsEntry("status", "CANCELLED");
    }

    @Test
    @DisplayName("R5: 취소하면 주문 수량만큼 모든 항목의 재고가 복원된다")
    void r5_cancelOrder_restoresStockForAllItems() {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 1000, 6);
        long orderId = idOf(postOrder(item(a, 4), item(b, 6)));
        assertThat(stockOf(a)).isEqualTo(6);
        assertThat(stockOf(b)).isZero();

        cancelOrder(orderId);

        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(stockOf(b)).isEqualTo(6);
    }

    @Test
    @DisplayName("R5: 이미 취소된 주문을 다시 취소하면 409이고 재고가 추가 복원되지 않는다")
    void r5_cancelOrder_twice_returns409WithoutExtraRestore() {
        long a = createProduct("A", 1000, 10);
        long orderId = idOf(postOrder(item(a, 4)));
        cancelOrder(orderId);

        ResponseEntity<Map> second = cancelOrder(orderId);

        assertProblem(second, HttpStatus.CONFLICT);
        assertThat(stockOf(a)).isEqualTo(10);
    }

    @Test
    @DisplayName("R5: 없는 주문 취소는 404")
    void r5_cancelOrder_unknownId_returns404() {
        assertProblem(cancelOrder(999_999L), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("R5: 숫자가 아닌 주문 id 취소는 400")
    void r5_cancelOrder_nonNumericId_returns400() {
        ResponseEntity<Map> res = rest.postForEntity("/api/orders/abc/cancel", null, Map.class);

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }
}
