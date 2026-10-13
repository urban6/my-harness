package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.order.support.AbstractIntegrationTest;

/** R3 주문 생성 (정상 / 400 / 404 / 409 / 원자성 / 오류 우선순위). */
class OrderCreateApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R3: 정상 주문은 201 + Location + status=ORDERED + unitPrice/totalPrice")
    void r3_createOrder_returns201WithLocationAndBody() {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 2500, 10);

        ResponseEntity<Map> res = postOrder(item(a, 2), item(b, 3));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = idOf(res);
        assertThat(res.getHeaders().getLocation().toString()).endsWith("/api/orders/" + orderId);
        assertThat(res.getBody()).containsOnlyKeys("id", "status", "totalPrice", "items", "createdAt")
                .containsEntry("status", "ORDERED")
                .containsEntry("totalPrice", 2 * 1000 + 3 * 2500);
        assertThat((List<Map<String, Object>>) res.getBody().get("items")).containsExactly(
                Map.of("productId", (int) a, "quantity", 2, "unitPrice", 1000),
                Map.of("productId", (int) b, "quantity", 3, "unitPrice", 2500));
    }

    @Test
    @DisplayName("R3: 주문하면 항목별 수량만큼 재고가 차감된다")
    void r3_createOrder_decreasesStockForEachItem() {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 2500, 4);

        postOrder(item(a, 2), item(b, 4));

        assertThat(stockOf(a)).isEqualTo(8);
        assertThat(stockOf(b)).isZero();
    }

    @Test
    @DisplayName("R3: 재고와 같은 수량(경계값)은 주문 가능하다")
    void r3_createOrder_quantityEqualToStock_succeeds() {
        long a = createProduct("A", 1000, 3);

        ResponseEntity<Map> res = postOrder(item(a, 3));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockOf(a)).isZero();
    }

    // ---- 400

    static Stream<Arguments> invalidOrderBodies() {
        return Stream.of(
                Arguments.of("items 빈 배열", "{\"items\":[]}"),
                Arguments.of("items 누락", "{}"),
                Arguments.of("items null", "{\"items\":null}"),
                Arguments.of("items 원소 null", "{\"items\":[null]}"),
                Arguments.of("quantity 0", "{\"items\":[{\"productId\":1,\"quantity\":0}]}"),
                Arguments.of("quantity 음수", "{\"items\":[{\"productId\":1,\"quantity\":-1}]}"),
                Arguments.of("quantity 누락", "{\"items\":[{\"productId\":1}]}"),
                Arguments.of("productId 누락", "{\"items\":[{\"quantity\":1}]}"),
                Arguments.of("productId 중복",
                        "{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}"));
    }

    @ParameterizedTest(name = "R3: {0} -> 400")
    @MethodSource("invalidOrderBodies")
    @DisplayName("R3: 검증 위반 요청은 400 problem+json")
    void r3_createOrder_invalidBody_returns400(String caseName, String json) {
        ResponseEntity<Map> res = postRaw("/api/orders", json);

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R3: 실제 존재하는 상품의 productId 중복도 400이며 재고는 차감되지 않는다")
    void r3_createOrder_duplicateExistingProduct_returns400AndKeepsStock() {
        long a = createProduct("A", 1000, 10);

        ResponseEntity<Map> res = postOrder(item(a, 1), item(a, 2));

        assertProblem(res, HttpStatus.BAD_REQUEST);
        assertThat(stockOf(a)).isEqualTo(10);
    }

    // ---- 404 / 409

    @Test
    @DisplayName("R3: 없는 상품이 포함되면 404이고 존재하는 상품의 재고도 차감되지 않는다")
    void r3_createOrder_unknownProduct_returns404() {
        long a = createProduct("A", 1000, 10);

        ResponseEntity<Map> res = postOrder(item(a, 1), item(999_999L, 1));

        assertProblem(res, HttpStatus.NOT_FOUND);
        assertThat(stockOf(a)).isEqualTo(10);
    }

    @Test
    @DisplayName("R3: 재고 부족이면 409이고 재고는 그대로다")
    void r3_createOrder_insufficientStock_returns409() {
        long a = createProduct("A", 1000, 2);

        ResponseEntity<Map> res = postOrder(item(a, 3));

        assertProblem(res, HttpStatus.CONFLICT);
        assertThat(stockOf(a)).isEqualTo(2);
    }

    @Test
    @DisplayName("R3 원자성: 두 항목 중 하나만 재고 부족이면 409이고 두 상품 모두 재고 불변")
    void r3_createOrder_partialInsufficientStock_isAtomic() {
        // productId 오름차순으로 차감하므로, 먼저 차감되는 a는 성공하고 나중의 b에서 실패한다 -> 롤백 확인
        long a = createProduct("A", 1000, 5);
        long b = createProduct("B", 1000, 1);
        long ordersBefore = totalOrders();

        ResponseEntity<Map> res = postOrder(item(a, 2), item(b, 2));

        assertProblem(res, HttpStatus.CONFLICT);
        assertThat(stockOf(a)).isEqualTo(5);
        assertThat(stockOf(b)).isEqualTo(1);
        assertThat(totalOrders()).isEqualTo(ordersBefore);
    }

    @Test
    @DisplayName("R3 원자성: 요청 순서를 뒤집어도(부족 항목이 앞) 두 상품 모두 재고 불변")
    void r3_createOrder_partialInsufficientStock_reversedOrder_isAtomic() {
        long a = createProduct("A", 1000, 5);
        long b = createProduct("B", 1000, 1);

        ResponseEntity<Map> res = postOrder(item(b, 2), item(a, 2));

        assertProblem(res, HttpStatus.CONFLICT);
        assertThat(stockOf(a)).isEqualTo(5);
        assertThat(stockOf(b)).isEqualTo(1);
    }

    // ---- 우선순위 400 -> 404 -> 409

    @Test
    @DisplayName("R3 우선순위: 400과 404가 섞이면 400")
    void r3_createOrder_mixed400And404_returns400() {
        long a = createProduct("A", 1000, 10);

        // 첫 항목은 없는 상품(404 후보), 둘째 항목은 quantity 0(400)
        ResponseEntity<Map> res = postOrder(item(999_999L, 1), item(a, 0));

        assertProblem(res, HttpStatus.BAD_REQUEST);
        assertThat(stockOf(a)).isEqualTo(10);
    }

    @Test
    @DisplayName("R3 우선순위: 404와 409가 섞이면 404 (재고 부족 항목이 앞에 있어도)")
    void r3_createOrder_mixed404And409_returns404() {
        long a = createProduct("A", 1000, 1);

        ResponseEntity<Map> res = postOrder(item(a, 5), item(999_999L, 1));

        assertProblem(res, HttpStatus.NOT_FOUND);
        assertThat(stockOf(a)).isEqualTo(1);
    }

    private long totalOrders() {
        ResponseEntity<Map> res = rest.getForEntity("/api/orders?size=1", Map.class);
        return ((Number) res.getBody().get("totalElements")).longValue();
    }
}
