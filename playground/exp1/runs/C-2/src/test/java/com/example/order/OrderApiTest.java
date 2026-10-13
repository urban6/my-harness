package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

/** 주문 API 확장 테스트: R3(생성) · R4(조회) · R5(취소) · R6(목록). 기본 happy path 는 OrderApiSmokeTest. */
class OrderApiTest extends AbstractApiTest {

    private int orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    @Nested
    @DisplayName("R3 주문 생성")
    class R3_CreateOrder {

        @Test
        @DisplayName("R3 정상 주문이면 201 + Location(/api/orders/{id}) + R4 형태(status=ORDERED) + 재고 차감")
        void valid_returns201_ORDERED_andDecreasesStock() {
            long p1 = createProduct("A", 1000, 10);
            long p2 = createProduct("B", 2000, 5);

            ResponseEntity<JsonNode> res = createOrder(List.of(item(p1, 3), item(p2, 5)));

            assertThat(res.getStatusCode().value()).isEqualTo(201);
            JsonNode body = res.getBody();
            assertOrderShape(body);
            assertThat(body.get("status").asText()).isEqualTo("ORDERED");
            assertThat(locationOf(res).toString()).endsWith("/api/orders/" + body.get("id").asLong());
            assertThat(stockOf(p1)).isEqualTo(7);
            assertThat(stockOf(p2)).isZero(); // 재고와 정확히 같은 수량은 허용
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"{}", "{\"items\":null}", "{\"items\":[]}"})
        @DisplayName("R3 items 가 누락/null/빈 배열이면 400 validation-error(field=items)")
        void itemsMissingOrEmpty_returns400(String json) {
            ResponseEntity<JsonNode> res = postRaw("/api/orders", json);

            assertProblem(res, 400, "validation-error");
            assertThat(res.getBody().get("errors").toString()).contains("\"field\":\"items\"");
            assertThat(orderCount()).isZero();
        }

        @ParameterizedTest(name = "quantity={0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("R3 quantity 가 1 미만이면 400 이고 재고/주문이 변하지 않는다")
        void quantityBelowOne_returns400(int quantity) {
            long p = createProduct("A", 1000, 5);

            ResponseEntity<JsonNode> res = createOrder(List.of(item(p, quantity)));

            assertProblem(res, 400, "validation-error");
            assertThat(res.getBody().get("errors").toString()).contains("\"field\":\"items[0].quantity\"");
            assertThat(stockOf(p)).isEqualTo(5);
            assertThat(orderCount()).isZero();
        }

        @Test
        @DisplayName("R3 quantity/productId 가 누락되면 400 validation-error")
        void missingQuantityOrProductId_returns400() {
            long p = createProduct("A", 1000, 5);

            ResponseEntity<JsonNode> noQuantity = postRaw("/api/orders", "{\"items\":[{\"productId\":" + p + "}]}");
            ResponseEntity<JsonNode> noProductId = postRaw("/api/orders", "{\"items\":[{\"quantity\":1}]}");

            assertProblem(noQuantity, 400, "validation-error");
            assertThat(noQuantity.getBody().get("errors").toString()).contains("items[0].quantity");
            assertProblem(noProductId, 400, "validation-error");
            assertThat(noProductId.getBody().get("errors").toString()).contains("items[0].productId");
        }

        @Test
        @DisplayName("R3 같은 productId 가 중복되면 400 이고 재고/주문이 변하지 않는다")
        void duplicateProductId_returns400() {
            long p = createProduct("A", 1000, 10);

            ResponseEntity<JsonNode> res = createOrder(List.of(item(p, 1), item(p, 2)));

            assertProblem(res, 400, "validation-error");
            assertThat(res.getBody().get("errors").toString()).contains("\"field\":\"items\"");
            assertThat(stockOf(p)).isEqualTo(10);
            assertThat(orderCount()).isZero();
        }

        @Test
        @DisplayName("R3 없는 상품이면 404 product-not-found 이고 주문이 생성되지 않는다")
        void unknownProduct_returns404() {
            long p = createProduct("A", 1000, 10);

            ResponseEntity<JsonNode> res = createOrder(List.of(item(p, 1), item(999999, 1)));

            assertProblem(res, 404, "product-not-found");
            assertThat(stockOf(p)).isEqualTo(10);
            assertThat(orderCount()).isZero();
        }

        @Test
        @DisplayName("R3 재고가 부족하면 409 insufficient-stock")
        void insufficientStock_returns409() {
            long p = createProduct("A", 1000, 2);

            ResponseEntity<JsonNode> res = createOrder(List.of(item(p, 3)));

            assertProblem(res, 409, "insufficient-stock");
            assertThat(stockOf(p)).isEqualTo(2);
            assertThat(orderCount()).isZero();
        }

        @Test
        @DisplayName("R3 재고 0 상품을 주문하면 409")
        void zeroStock_returns409() {
            long p = createProduct("품절", 1000, 0);

            assertProblem(createOrder(List.of(item(p, 1))), 409, "insufficient-stock");
        }

        @Test
        @DisplayName("R3 원자성: 다항목 중 하나만 부족해도 409 이고 모든 상품 재고 불변 + 주문 미생성")
        void oneShortItem_rollsBackEverything() {
            long a = createProduct("A", 1000, 10);
            long b = createProduct("B", 1000, 10);
            long c = createProduct("C", 1000, 1);
            long d = createProduct("D", 1000, 10);

            ResponseEntity<JsonNode> res = createOrder(List.of(item(a, 4), item(b, 10), item(c, 2), item(d, 1)));

            assertProblem(res, 409, "insufficient-stock");
            assertThat(stockOf(a)).isEqualTo(10);
            assertThat(stockOf(b)).isEqualTo(10);
            assertThat(stockOf(c)).isEqualTo(1);
            assertThat(stockOf(d)).isEqualTo(10);
            assertThat(orderCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
        }

        @Test
        @DisplayName("R3 우선순위: 400 사유(quantity 0)가 있으면 없는 상품이어도 400")
        void priority_400_beats_404() {
            ResponseEntity<JsonNode> res = createOrder(List.of(item(999, 0)));

            assertProblem(res, 400, "validation-error");
        }

        @Test
        @DisplayName("R3 우선순위: [{없는 상품},{재고 부족 상품}] 이면 404")
        void priority_404_beats_409() {
            long low = createProduct("부족", 1000, 1);

            assertProblem(createOrder(List.of(item(999999, 1), item(low, 5))), 404, "product-not-found");
            assertProblem(createOrder(List.of(item(low, 5), item(999999, 1))), 404, "product-not-found");
            assertThat(stockOf(low)).isEqualTo(1);
        }

        @Test
        @DisplayName("R3 우선순위: 400 사유가 하나라도 있으면 404/409 사유가 함께 있어도 400")
        void priority_400_beats_409() {
            long low = createProduct("부족", 1000, 1);

            ResponseEntity<JsonNode> res = createOrder(List.of(item(low, 5), item(low, 1)));

            assertProblem(res, 400, "validation-error");
        }
    }

    @Nested
    @DisplayName("R4 주문 조회")
    class R4_GetOrder {

        @Test
        @DisplayName("R4 다항목 주문의 totalPrice 는 Σ(unitPrice×quantity), unitPrice 는 상품 가격")
        void multiItem_totalPriceIsSum() {
            long p1 = createProduct("A", 1500, 10);
            long p2 = createProduct("B", 700, 10);
            long p3 = createProduct("C", 20000, 10);
            long orderId = createOrder(List.of(item(p1, 3), item(p2, 4), item(p3, 1))).getBody().get("id").asLong();

            JsonNode body = get("/api/orders/" + orderId).getBody();

            assertOrderShape(body);
            assertThat(body.get("totalPrice").asLong()).isEqualTo(1500L * 3 + 700L * 4 + 20000L);
            assertThat(body.get("items")).hasSize(3);
            assertThat(body.get("items").get(0).get("productId").asLong()).isEqualTo(p1);
            assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
            assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(1500);
            assertThat(body.get("items").get(1).get("unitPrice").asLong()).isEqualTo(700);
            assertThat(body.get("items").get(2).get("unitPrice").asLong()).isEqualTo(20000);
        }

        @Test
        @DisplayName("R4 unitPrice 는 주문 시점 가격의 스냅샷이다(이후 상품 가격이 바뀌어도 불변)")
        void unitPrice_isSnapshotAtOrderTime() {
            long p = createProduct("A", 1000, 10);
            long orderId = createOrder(List.of(item(p, 2))).getBody().get("id").asLong();

            jdbc.update("UPDATE products SET price = 9999 WHERE id = ?", p);
            JsonNode body = get("/api/orders/" + orderId).getBody();

            assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(1000);
            assertThat(body.get("totalPrice").asLong()).isEqualTo(2000);
        }

        @Test
        @DisplayName("R4 createdAt 은 Instant.parse 로 파싱되는 ISO-8601 문자열이다")
        void createdAt_isIso8601() {
            long p = createProduct("A", 1000, 10);
            Instant before = Instant.now().minusSeconds(60);
            long orderId = createOrder(List.of(item(p, 1))).getBody().get("id").asLong();

            String createdAt = get("/api/orders/" + orderId).getBody().get("createdAt").asText();

            assertThat(Instant.parse(createdAt)).isBetween(before, Instant.now().plusSeconds(60));
        }

        @Test
        @DisplayName("R4 없는 주문이면 404 order-not-found")
        void unknownOrder_returns404() {
            assertProblem(get("/api/orders/424242"), 404, "order-not-found");
        }

        @Test
        @DisplayName("R4 path id 가 정수가 아니면(abc) 400 validation-error")
        void nonNumericId_returns400() {
            assertProblem(get("/api/orders/abc"), 400, "validation-error");
        }
    }

    @Nested
    @DisplayName("R5 주문 취소")
    class R5_CancelOrder {

        @Test
        @DisplayName("R5 취소하면 200 + R4 형태 + status=CANCELLED + 다항목 재고 전부 복원")
        void cancel_restoresAllStock() {
            long p1 = createProduct("A", 1000, 10);
            long p2 = createProduct("B", 2000, 8);
            long p3 = createProduct("C", 3000, 3);
            long orderId = createOrder(List.of(item(p1, 4), item(p2, 8), item(p3, 1))).getBody().get("id").asLong();
            assertThat(stockOf(p1)).isEqualTo(6);
            assertThat(stockOf(p2)).isZero();
            assertThat(stockOf(p3)).isEqualTo(2);

            ResponseEntity<JsonNode> res = postNoBody("/api/orders/" + orderId + "/cancel");

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertOrderShape(res.getBody());
            assertThat(res.getBody().get("id").asLong()).isEqualTo(orderId);
            assertThat(res.getBody().get("status").asText()).isEqualTo("CANCELLED");
            assertThat(stockOf(p1)).isEqualTo(10);
            assertThat(stockOf(p2)).isEqualTo(8);
            assertThat(stockOf(p3)).isEqualTo(3);
            assertThat(get("/api/orders/" + orderId).getBody().get("status").asText()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("R5 재취소는 409 order-already-cancelled 이고 재고가 이중 복원되지 않는다")
        void secondCancel_returns409_withoutDoubleRestore() {
            long p = createProduct("A", 1000, 10);
            long orderId = createOrder(List.of(item(p, 4))).getBody().get("id").asLong();
            postNoBody("/api/orders/" + orderId + "/cancel");
            assertThat(stockOf(p)).isEqualTo(10);

            ResponseEntity<JsonNode> res = postNoBody("/api/orders/" + orderId + "/cancel");

            assertProblem(res, 409, "order-already-cancelled");
            assertThat(stockOf(p)).isEqualTo(10);
        }

        @Test
        @DisplayName("R5 없는 주문을 취소하면 404 order-not-found")
        void unknownOrder_returns404() {
            assertProblem(postNoBody("/api/orders/424242/cancel"), 404, "order-not-found");
        }

        @Test
        @DisplayName("R5 path id 가 정수가 아니면(abc) 400 validation-error")
        void nonNumericId_returns400() {
            assertProblem(postNoBody("/api/orders/abc/cancel"), 400, "validation-error");
        }

        @Test
        @DisplayName("R5 취소 후 다른 주문의 재고에는 영향이 없다")
        void cancel_doesNotAffectOtherOrders() {
            long p = createProduct("A", 1000, 10);
            long o1 = createOrder(List.of(item(p, 2))).getBody().get("id").asLong();
            long o2 = createOrder(List.of(item(p, 3))).getBody().get("id").asLong();

            postNoBody("/api/orders/" + o1 + "/cancel");

            assertThat(stockOf(p)).isEqualTo(7);
            assertThat(get("/api/orders/" + o2).getBody().get("status").asText()).isEqualTo("ORDERED");
        }
    }

    @Nested
    @DisplayName("R6 주문 목록")
    class R6_ListOrders {

        private List<Long> createOrders(int n) {
            long p = createProduct("상품", 1000, 1000);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ids.add(createOrder(List.of(item(p, 1))).getBody().get("id").asLong());
            }
            return ids;
        }

        @Test
        @DisplayName("R6 주문이 없으면 기본 page 0/size 20, 빈 content, totalElements 0")
        void empty_returnsDefaultsAndEmptyContent() {
            ResponseEntity<JsonNode> res = get("/api/orders");

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            JsonNode body = res.getBody();
            assertThat(fieldNames(body)).containsExactlyInAnyOrder("content", "page", "size", "totalElements");
            assertThat(body.get("content").isArray()).isTrue();
            assertThat(body.get("content")).isEmpty();
            assertThat(body.get("page").asInt()).isZero();
            assertThat(body.get("size").asInt()).isEqualTo(20);
            assertThat(body.get("totalElements").asLong()).isZero();
        }

        @Test
        @DisplayName("R6 파라미터 없이 호출하면 기본 size 20 으로 최대 20건, content 원소는 R4 형태")
        void defaultPaging_returns20_andElementsAreR4Shape() {
            createOrders(22);

            JsonNode body = get("/api/orders").getBody();

            assertThat(body.get("content")).hasSize(20);
            assertThat(body.get("totalElements").asLong()).isEqualTo(22);
            assertThat(body.get("page").asInt()).isZero();
            assertThat(body.get("size").asInt()).isEqualTo(20);
            body.get("content").forEach(AbstractApiTest::assertOrderShape);
        }

        @Test
        @DisplayName("R6 createdAt 내림차순이 id 보다 우선하고, createdAt 이 같으면 id 내림차순")
        void sortedByCreatedAtDesc_thenIdDesc() {
            List<Long> ids = createOrders(5); // ids[0..4], id 오름차순
            // ids[0..2] 는 같은 시각 T, ids[3] 는 T 보다 1시간 뒤, ids[4] 는 T 보다 1시간 앞
            jdbc.update("UPDATE orders SET created_at = TIMESTAMPTZ '2026-01-01 12:00:00+00' WHERE id IN (?, ?, ?)",
                    ids.get(0), ids.get(1), ids.get(2));
            jdbc.update("UPDATE orders SET created_at = TIMESTAMPTZ '2026-01-01 13:00:00+00' WHERE id = ?", ids.get(3));
            jdbc.update("UPDATE orders SET created_at = TIMESTAMPTZ '2026-01-01 11:00:00+00' WHERE id = ?", ids.get(4));

            JsonNode content = get("/api/orders").getBody().get("content");

            List<Long> actual = new ArrayList<>();
            content.forEach(n -> actual.add(n.get("id").asLong()));
            assertThat(actual).containsExactly(ids.get(3), ids.get(2), ids.get(1), ids.get(0), ids.get(4));
        }

        @Test
        @DisplayName("R6 같은 createdAt 이어도 페이지 경계에서 id 내림차순 순서가 유지된다")
        void tieBreakerIsStableAcrossPages() {
            List<Long> ids = createOrders(5);
            jdbc.update("UPDATE orders SET created_at = TIMESTAMPTZ '2026-01-01 12:00:00+00'");

            List<Long> actual = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                get("/api/orders?page=" + page + "&size=2").getBody().get("content")
                        .forEach(n -> actual.add(n.get("id").asLong()));
            }

            List<Long> expected = new ArrayList<>(ids);
            java.util.Collections.reverse(expected);
            assertThat(actual).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("R6 page/size 를 지정하면 응답에 그대로 반영되고 해당 페이지 구간만 반환")
        void explicitPaging_echoesParamsAndSlices() {
            List<Long> ids = createOrders(5);

            JsonNode body = get("/api/orders?page=1&size=2").getBody();

            assertThat(body.get("page").asInt()).isEqualTo(1);
            assertThat(body.get("size").asInt()).isEqualTo(2);
            assertThat(body.get("totalElements").asLong()).isEqualTo(5);
            assertThat(body.get("content")).hasSize(2);
            assertThat(body.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(2));
            assertThat(body.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(1));
        }

        @Test
        @DisplayName("R6 size 100 은 허용(경계값), size 1 도 허용")
        void sizeBoundaries_areAllowed() {
            createOrders(2);

            ResponseEntity<JsonNode> max = get("/api/orders?size=100");
            ResponseEntity<JsonNode> min = get("/api/orders?size=1");

            assertThat(max.getStatusCode().value()).isEqualTo(200);
            assertThat(max.getBody().get("size").asInt()).isEqualTo(100);
            assertThat(min.getStatusCode().value()).isEqualTo(200);
            assertThat(min.getBody().get("content")).hasSize(1);
        }

        @Test
        @DisplayName("R6 범위를 넘는 page 는 빈 content 이고 totalElements 는 전체 건수")
        void pageBeyondRange_returnsEmptyContent() {
            createOrders(3);

            ResponseEntity<JsonNode> res = get("/api/orders?page=5&size=2");

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody().get("content")).isEmpty();
            assertThat(res.getBody().get("page").asInt()).isEqualTo(5);
            assertThat(res.getBody().get("totalElements").asLong()).isEqualTo(3);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"page=-1", "size=0", "size=-5", "size=101", "page=abc", "size=abc"})
        @DisplayName("R6 page/size 가 범위 밖이거나 정수가 아니면 400 validation-error")
        void invalidParams_return400(String query) {
            assertProblem(get("/api/orders?" + query), 400, "validation-error");
        }

        @Test
        @DisplayName("R6 취소된 주문도 목록에 CANCELLED 로 포함된다")
        void cancelledOrder_isListedWithStatus() {
            long p = createProduct("A", 1000, 10);
            long orderId = createOrder(List.of(item(p, 1))).getBody().get("id").asLong();
            postNoBody("/api/orders/" + orderId + "/cancel");

            JsonNode body = get("/api/orders").getBody();

            assertThat(body.get("totalElements").asLong()).isEqualTo(1);
            assertThat(body.get("content").get(0).get("status").asText()).isEqualTo("CANCELLED");
        }
    }
}
