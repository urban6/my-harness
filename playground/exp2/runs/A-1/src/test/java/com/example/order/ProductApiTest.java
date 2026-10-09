package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R1. 상품")
class ProductApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("R1.1 등록하면 201 + Location, 본문은 조회와 같은 형태이고 reserved=0")
    void createProduct() {
        ResponseEntity<JsonNode> response = post("/api/products", map("name", "키보드", "price", 35000, "stock", 7));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode body = response.getBody();
        long id = body.get("id").asLong();
        assertThat(response.getHeaders().getLocation()).hasToString("/api/products/" + id);
        assertThat(body.get("name").asText()).isEqualTo("키보드");
        assertThat(body.get("price").asLong()).isEqualTo(35000);
        assertThat(body.get("stock").asInt()).isEqualTo(7);
        assertThat(body.get("reserved").asInt()).isZero();
        assertThat(body.get("available").asInt()).isEqualTo(7);

        assertThat(get(response.getHeaders().getLocation().toString()).getBody()).isEqualTo(body);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaryValues")
    @DisplayName("R1.2 경계값은 허용된다")
    void acceptsBoundaries(String description, Map<String, Object> body) {
        assertThat(post("/api/products", body).getStatusCode().value()).isEqualTo(201);
    }

    static Stream<Arguments> boundaryValues() {
        return Stream.of(
                Arguments.of("name 100자", map("name", "a".repeat(100), "price", 1, "stock", 0)),
                Arguments.of("price 최소 1", map("name", "p", "price", 1, "stock", 1)),
                Arguments.of("price 최대 10,000,000", map("name", "p", "price", 10_000_000, "stock", 1)),
                Arguments.of("stock 최소 0", map("name", "p", "price", 100, "stock", 0)),
                Arguments.of("stock 최대 1,000,000", map("name", "p", "price", 100, "stock", 1_000_000)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    @DisplayName("R1.2 규칙을 어기면 400")
    void rejectsInvalid(String description, Object body) {
        assertProblem(post("/api/products", body), 400, "VALIDATION_ERROR");
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("name 누락", map("price", 100, "stock", 1)),
                Arguments.of("name 공백만", map("name", "   ", "price", 100, "stock", 1)),
                Arguments.of("name 101자", map("name", "a".repeat(101), "price", 100, "stock", 1)),
                Arguments.of("price 누락", map("name", "p", "stock", 1)),
                Arguments.of("price 0", map("name", "p", "price", 0, "stock", 1)),
                Arguments.of("price 10,000,001", map("name", "p", "price", 10_000_001, "stock", 1)),
                Arguments.of("price 소수", map("name", "p", "price", 1.5, "stock", 1)),
                Arguments.of("stock 누락", map("name", "p", "price", 100)),
                Arguments.of("stock -1", map("name", "p", "price", 100, "stock", -1)),
                Arguments.of("stock 1,000,001", map("name", "p", "price", 100, "stock", 1_000_001)),
                Arguments.of("JSON 파싱 실패", "{\"name\": \"p\", \"price\": "));
    }

    @Test
    @DisplayName("R1.3 없는 상품 조회는 404")
    void productNotFound() {
        assertProblem(get("/api/products/999999999"), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.4 available = stock − reserved (결제 대기 주문이 잡은 수량, 결제되면 stock에서 빠짐)")
    void availableReflectsReservations() {
        long productId = createProduct(1000, 10);

        long orderId = placeOrder(newUser(), null, item(productId, 3)).get("id").asLong();
        JsonNode reserved = product(productId);
        assertThat(reserved.get("stock").asInt()).isEqualTo(10);
        assertThat(reserved.get("reserved").asInt()).isEqualTo(3);
        assertThat(reserved.get("available").asInt()).isEqualTo(7);

        assertThat(pay(orderId, newKey(), "card-ok").getStatusCode().value()).isEqualTo(200);
        JsonNode sold = product(productId);
        assertThat(sold.get("stock").asInt()).isEqualTo(7);
        assertThat(sold.get("reserved").asInt()).isZero();
        assertThat(sold.get("available").asInt()).isEqualTo(7);
    }
}
