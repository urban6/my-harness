package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R3 주문 생성")
class OrderCreateApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("재고가 충분하면 201, Location, 주문 조회와 같은 형태(status=ORDERED)로 응답하고 재고를 차감한다")
    void createsOrderAndDeductsStock() {
        long keyboard = createProduct("Keyboard", 30000, 5);
        long mouse = createProduct("Mouse", 10000, 10);

        ResponseEntity<String> response = createOrder(keyboard, 2, mouse, 3);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode body = json(response);
        long id = body.get("id").asLong();
        assertThat(body.get("status").asText()).isEqualTo("ORDERED");
        assertThat(body.get("totalPrice").asLong()).isEqualTo(2 * 30000 + 3 * 10000);
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("productId").asLong()).isEqualTo(keyboard);
        assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(30000);
        assertThat(body.get("items").get(1).get("productId").asLong()).isEqualTo(mouse);
        assertThat(body.get("createdAt").isTextual()).isTrue();
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/api/orders/" + id);

        ResponseEntity<String> fetched = get(response.getHeaders().getLocation().getPath());
        assertThat(json(fetched)).isEqualTo(body);

        assertThat(stockOf(keyboard)).isEqualTo(3);
        assertThat(stockOf(mouse)).isEqualTo(7);
    }

    @Test
    @DisplayName("재고와 정확히 같은 수량은 주문할 수 있다")
    void allowsOrderingEntireStock() {
        long product = createProduct("Last", 1000, 2);

        assertThat(createOrder(product, 2).getStatusCode().value()).isEqualTo(201);
        assertThat(stockOf(product)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{}",
            "{\"items\":null}",
            "{\"items\":[]}",
            "{\"items\":[null]}",
            "{\"items\":[{\"quantity\":1}]}",
            "{\"items\":[{\"productId\":1}]}",
            "{\"items\":[{\"productId\":1,\"quantity\":0}]}",
            "{\"items\":[{\"productId\":1,\"quantity\":-3}]}",
            "{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}"
    })
    @DisplayName("items 비어 있음, quantity < 1, productId 중복 등 규칙 위반은 400")
    void rejectsInvalidRequest(String body) {
        createProduct("Exists", 1000, 10);

        assertProblem(post("/api/orders", body), 400);
        assertThat(stockOf(1)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
    }

    @Test
    @DisplayName("없는 상품이 포함되면 404이고 다른 상품 재고도 차감되지 않는다")
    void notFoundProduct() {
        long product = createProduct("Exists", 1000, 10);

        assertProblem(createOrder(product, 1, 999999, 1), 404);
        assertThat(stockOf(product)).isEqualTo(10);
    }

    @Test
    @DisplayName("재고가 부족하면 409이고 어떤 상품의 재고도 차감되지 않는다(전부 아니면 전무)")
    void insufficientStockIsAtomic() {
        long plenty = createProduct("Plenty", 1000, 100);
        long scarce = createProduct("Scarce", 2000, 1);

        assertProblem(createOrder(plenty, 5, scarce, 2), 409);

        assertThat(stockOf(plenty)).isEqualTo(100);
        assertThat(stockOf(scarce)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
    }

    @Test
    @DisplayName("오류가 여럿이면 400이 404보다 우선한다")
    void badRequestTakesPrecedenceOverNotFound() {
        assertProblem(post("/api/orders",
                "{\"items\":[{\"productId\":999999,\"quantity\":1},{\"productId\":999999,\"quantity\":1}]}"), 400);
        assertProblem(post("/api/orders",
                "{\"items\":[{\"productId\":999999,\"quantity\":0}]}"), 400);
    }

    @Test
    @DisplayName("오류가 여럿이면 404가 409보다 우선한다")
    void notFoundTakesPrecedenceOverConflict() {
        long scarce = createProduct("Scarce", 1000, 0);

        assertProblem(createOrder(scarce, 1, 999999, 1), 404);
        assertProblem(createOrder(999999, 1, scarce, 1), 404);
    }
}
