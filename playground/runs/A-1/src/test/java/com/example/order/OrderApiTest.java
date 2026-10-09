package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OrderApiTest extends ApiTestSupport {

    @Nested
    @DisplayName("R3 주문 생성")
    class CreateOrder {

        @Test
        @DisplayName("201 + Location, status=ORDERED, 재고 차감")
        void createsOrderAndDecreasesStock() {
            long keyboard = createProduct("키보드", 30000, 10);
            long mouse = createProduct("마우스", 15000, 5);

            ResponseEntity<String> response = placeOrder(items(keyboard, 2, mouse, 5));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode body = json(response);
            long id = body.get("id").asLong();
            assertThat(response.getHeaders().getLocation()).isNotNull();
            assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/api/orders/" + id);
            assertThat(body.get("status").asText()).isEqualTo("ORDERED");
            assertThat(body.get("totalPrice").asLong()).isEqualTo(30000 * 2 + 15000 * 5);
            assertThat(body.get("items")).hasSize(2);
            assertThat(body.get("items").get(0).get("productId").asLong()).isEqualTo(keyboard);
            assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
            assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(30000);
            assertThat(body.get("items").get(1).get("productId").asLong()).isEqualTo(mouse);
            assertThat(body.get("items").get(1).get("quantity").asInt()).isEqualTo(5);
            assertThat(body.get("items").get(1).get("unitPrice").asLong()).isEqualTo(15000);
            assertThat(Instant.parse(body.get("createdAt").asText())).isBeforeOrEqualTo(Instant.now());

            assertThat(stockOf(keyboard)).isEqualTo(8);
            assertThat(stockOf(mouse)).isZero();

            ResponseEntity<String> fetched = get(response.getHeaders().getLocation().getPath());
            assertThat(json(fetched)).isEqualTo(body);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "{}",
                "{\"items\":null}",
                "{\"items\":[]}",
                "{\"items\":[null]}",
                "{\"items\":[{\"quantity\":1}]}",
                "{\"items\":[{\"productId\":1}]}",
                "{\"items\":[{\"productId\":1,\"quantity\":0}]}",
                "{\"items\":[{\"productId\":1,\"quantity\":-1}]}",
                "{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}",
        })
        @DisplayName("items 비어있음·quantity < 1·productId 중복 등은 400, 재고 변화 없음")
        void rejectsInvalidRequest(String body) {
            long productId = createProduct("상품", 1000, 10);
            assertThat(productId).isEqualTo(1);

            assertProblem(post("/api/orders", body), HttpStatus.BAD_REQUEST);
            assertThat(stockOf(productId)).isEqualTo(10);
            assertThat(countOrders()).isZero();
        }

        @Test
        @DisplayName("없는 상품이 있으면 404, 재고 변화 없음")
        void returns404ForUnknownProduct() {
            long productId = createProduct("상품", 1000, 10);

            assertProblem(placeOrder(items(productId, 1, 999_999L, 1)), HttpStatus.NOT_FOUND);
            assertThat(stockOf(productId)).isEqualTo(10);
            assertThat(countOrders()).isZero();
        }

        @Test
        @DisplayName("재고 부족이면 409, 다른 항목의 재고도 차감되지 않는다")
        void returns409AndKeepsAllStockWhenAnyItemIsShort() {
            long plenty = createProduct("넉넉", 1000, 10);
            long scarce = createProduct("부족", 2000, 1);

            assertProblem(placeOrder(items(plenty, 5, scarce, 2)), HttpStatus.CONFLICT);
            assertThat(stockOf(plenty)).isEqualTo(10);
            assertThat(stockOf(scarce)).isEqualTo(1);
            assertThat(countOrders()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
        }

        @Test
        @DisplayName("재고와 같은 수량은 주문 가능하고 재고는 0이 된다")
        void allowsOrderingExactStock() {
            long productId = createProduct("상품", 1000, 3);

            ResponseEntity<String> response = placeOrder(items(productId, 3));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(stockOf(productId)).isZero();
        }

        @Test
        @DisplayName("오류가 여럿이면 400 → 404 → 409 순으로 우선한다")
        void appliesErrorPrecedence() {
            long scarce = createProduct("부족", 1000, 1);

            // 400(중복) + 404(없는 상품)
            assertProblem(post("/api/orders",
                    "{\"items\":[{\"productId\":999,\"quantity\":1},{\"productId\":999,\"quantity\":1}]}"),
                    HttpStatus.BAD_REQUEST);
            // 400(quantity 0) + 404(없는 상품) + 409(재고 부족)
            assertProblem(post("/api/orders",
                    "{\"items\":[{\"productId\":999,\"quantity\":0},{\"productId\":%d,\"quantity\":5}]}".formatted(scarce)),
                    HttpStatus.BAD_REQUEST);
            // 404(없는 상품) + 409(재고 부족) — 재고 부족 항목이 앞에 있어도 404
            assertProblem(placeOrder(items(scarce, 5, 999L, 1)), HttpStatus.NOT_FOUND);

            assertThat(stockOf(scarce)).isEqualTo(1);
            assertThat(countOrders()).isZero();
        }
    }

    @Nested
    @DisplayName("R4 주문 조회")
    class GetOrder {

        @Test
        @DisplayName("200 {id, status, totalPrice, items[], createdAt}")
        void returnsOrder() {
            long productId = createProduct("상품", 2500, 10);
            long orderId = createOrder(items(productId, 4));

            ResponseEntity<String> response = get("/api/orders/" + orderId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = json(response);
            assertThat(body.get("id").asLong()).isEqualTo(orderId);
            assertThat(body.get("status").asText()).isEqualTo("ORDERED");
            assertThat(body.get("totalPrice").asLong()).isEqualTo(10000);
            assertThat(body.get("items")).hasSize(1);
            JsonNode item = body.get("items").get(0);
            assertThat(item.get("productId").asLong()).isEqualTo(productId);
            assertThat(item.get("quantity").asInt()).isEqualTo(4);
            assertThat(item.get("unitPrice").asLong()).isEqualTo(2500);
            assertThat(item.size()).isEqualTo(3);
            assertThat(Instant.parse(body.get("createdAt").asText())).isNotNull();
            assertThat(body.size()).isEqualTo(5);
        }

        @Test
        @DisplayName("unitPrice는 주문 시점 가격이며, 이후 상품 가격이 바뀌어도 유지된다")
        void keepsUnitPriceAtOrderTime() {
            long productId = createProduct("상품", 1000, 10);
            long orderId = createOrder(items(productId, 3));

            jdbc.update("UPDATE products SET price = 9999 WHERE id = ?", productId);

            JsonNode body = json(get("/api/orders/" + orderId));
            assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(1000);
            assertThat(body.get("totalPrice").asLong()).isEqualTo(3000);
        }

        @Test
        @DisplayName("없으면 404")
        void returns404WhenMissing() {
            assertProblem(get("/api/orders/999999"), HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("R5 주문 취소")
    class CancelOrder {

        @Test
        @DisplayName("200, status=CANCELLED, 재고 복원")
        void cancelsOrderAndRestoresStock() {
            long a = createProduct("A", 1000, 10);
            long b = createProduct("B", 500, 4);
            long orderId = createOrder(items(a, 3, b, 4));
            assertThat(stockOf(a)).isEqualTo(7);
            assertThat(stockOf(b)).isZero();

            ResponseEntity<String> response = post("/api/orders/" + orderId + "/cancel");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = json(response);
            assertThat(body.get("id").asLong()).isEqualTo(orderId);
            assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
            assertThat(body.get("totalPrice").asLong()).isEqualTo(5000);
            assertThat(body.get("items")).hasSize(2);
            assertThat(body.has("createdAt")).isTrue();
            assertThat(stockOf(a)).isEqualTo(10);
            assertThat(stockOf(b)).isEqualTo(4);

            assertThat(json(get("/api/orders/" + orderId))).isEqualTo(body);
        }

        @Test
        @DisplayName("이미 취소된 주문은 409, 재고는 다시 복원되지 않는다")
        void returns409WhenAlreadyCancelled() {
            long productId = createProduct("상품", 1000, 10);
            long orderId = createOrder(items(productId, 2));
            assertThat(post("/api/orders/" + orderId + "/cancel").getStatusCode()).isEqualTo(HttpStatus.OK);

            assertProblem(post("/api/orders/" + orderId + "/cancel"), HttpStatus.CONFLICT);
            assertThat(stockOf(productId)).isEqualTo(10);
        }

        @Test
        @DisplayName("없으면 404")
        void returns404WhenMissing() {
            assertProblem(post("/api/orders/999999/cancel"), HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("R6 주문 목록")
    class ListOrders {

        @Test
        @DisplayName("기본값 page=0, size=20, 원소는 주문 조회 응답과 같은 형태")
        void usesDefaultsAndOrderShape() {
            long productId = createProduct("상품", 1000, 100);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ids.add(createOrder(items(productId, i + 1)));
            }

            ResponseEntity<String> response = get("/api/orders");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = json(response);
            assertThat(body.get("page").asInt()).isZero();
            assertThat(body.get("size").asInt()).isEqualTo(20);
            assertThat(body.get("totalElements").asLong()).isEqualTo(3);
            assertThat(body.get("content")).hasSize(3);
            for (JsonNode order : body.get("content")) {
                assertThat(order).isEqualTo(json(get("/api/orders/" + order.get("id").asLong())));
            }
        }

        @Test
        @DisplayName("createdAt 내림차순, 같으면 id 내림차순")
        void sortsByCreatedAtDescThenIdDesc() {
            long productId = createProduct("상품", 1000, 100);
            long first = createOrder(items(productId, 1));
            long second = createOrder(items(productId, 1));
            long third = createOrder(items(productId, 1));
            long fourth = createOrder(items(productId, 1));
            jdbc.update("UPDATE orders SET created_at = '2026-01-03T00:00:00Z' WHERE id IN (?, ?)", first, third);
            jdbc.update("UPDATE orders SET created_at = '2026-01-01T00:00:00Z' WHERE id = ?", second);
            jdbc.update("UPDATE orders SET created_at = '2026-01-05T00:00:00Z' WHERE id = ?", fourth);

            JsonNode content = json(get("/api/orders")).get("content");

            List<Long> ids = new ArrayList<>();
            content.forEach(order -> ids.add(order.get("id").asLong()));
            assertThat(ids).containsExactly(fourth, third, first, second);
        }

        @Test
        @DisplayName("page·size로 페이지를 나눈다")
        void paginates() {
            long productId = createProduct("상품", 1000, 100);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ids.add(createOrder(items(productId, 1)));
            }

            JsonNode body = json(get("/api/orders?page=1&size=2"));

            assertThat(body.get("page").asInt()).isEqualTo(1);
            assertThat(body.get("size").asInt()).isEqualTo(2);
            assertThat(body.get("totalElements").asLong()).isEqualTo(5);
            List<Long> pageIds = new ArrayList<>();
            body.get("content").forEach(order -> pageIds.add(order.get("id").asLong()));
            assertThat(pageIds).containsExactly(ids.get(2), ids.get(1));

            JsonNode last = json(get("/api/orders?page=2&size=2"));
            assertThat(last.get("content")).hasSize(1);
            assertThat(last.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(0));

            JsonNode beyond = json(get("/api/orders?page=10&size=2"));
            assertThat(beyond.get("content")).isEmpty();
            assertThat(beyond.get("totalElements").asLong()).isEqualTo(5);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"size=1", "size=100", "page=0&size=50"})
        @DisplayName("경계값 size 1·100은 허용")
        void acceptsBoundaryValues(String query) {
            assertThat(get("/api/orders?" + query).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"page=-1", "size=0", "size=101", "size=-5", "page=abc", "size=abc"})
        @DisplayName("범위 밖이면 400")
        void rejectsOutOfRange(String query) {
            assertProblem(get("/api/orders?" + query), HttpStatus.BAD_REQUEST);
        }
    }
}
