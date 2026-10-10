package com.example.order;

import static com.example.order.support.TestApi.item;
import static com.example.order.support.TestApi.newUserId;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTest;
import com.example.order.support.TestApi.Response;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("R1 상품")
class ProductApiTest extends IntegrationTest {

    @Test
    @DisplayName("R1.1 상품 등록 → 201 + Location, reserved=0, available=stock")
    void create_returns201WithLocation() {
        Response response = api.post("/api/products", Map.of("name", "키보드", "price", 50_000, "stock", 7))
                .assertStatus(201);

        JsonNode body = response.body();
        assertThat(response.header("Location")).endsWith("/api/products/" + body.get("id").asLong());
        assertThat(body.get("name").asText()).isEqualTo("키보드");
        assertThat(body.get("price").asLong()).isEqualTo(50_000);
        assertThat(body.get("stock").asInt()).isEqualTo(7);
        assertThat(body.get("reserved").asInt()).isZero();
        assertThat(body.get("available").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("R1.2 경계값(name 100자, price 1·10,000,000, stock 0·1,000,000)은 허용")
    void create_acceptsBoundaryValues() {
        api.post("/api/products", Map.of("name", "a".repeat(100), "price", 1, "stock", 0)).assertStatus(201);
        api.post("/api/products", Map.of("name", "b", "price", 10_000_000, "stock", 1_000_000)).assertStatus(201);
    }

    static Stream<Arguments> invalidProducts() {
        return Stream.of(
                Arguments.of("name 누락", body(null, 1000L, 1)),
                Arguments.of("name 공백만", body("   ", 1000L, 1)),
                Arguments.of("name 101자", body("a".repeat(101), 1000L, 1)),
                Arguments.of("price 누락", body("p", null, 1)),
                Arguments.of("price 0", body("p", 0L, 1)),
                Arguments.of("price 10,000,001", body("p", 10_000_001L, 1)),
                Arguments.of("stock 누락", body("p", 1000L, null)),
                Arguments.of("stock -1", body("p", 1000L, -1)),
                Arguments.of("stock 1,000,001", body("p", 1000L, 1_000_001)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidProducts")
    @DisplayName("R1.2 검증 위반 → 400 VALIDATION_ERROR")
    void create_rejectsInvalid(String caseName, Map<String, Object> body) {
        api.post("/api/products", body).assertProblem(400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.3 상품 조회 → 200, 없으면 404 PRODUCT_NOT_FOUND")
    void get_returnsProductOr404() {
        long id = api.createProduct(1200, 3);

        JsonNode body = api.get("/api/products/" + id).assertStatus(200).body();
        assertThat(body.get("id").asLong()).isEqualTo(id);
        assertThat(body.get("price").asLong()).isEqualTo(1200);
        assertThat(body.get("stock").asInt()).isEqualTo(3);
        assertThat(body.get("reserved").asInt()).isZero();
        assertThat(body.get("available").asInt()).isEqualTo(3);

        api.get("/api/products/999999999").assertProblem(404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.4 결제 대기 주문은 reserved를, 결제 완료는 stock을 줄이고 available = stock − reserved")
    void stockReservedAvailable_followOrderLifecycle() {
        long id = api.createProduct(1000, 10);

        long orderId = api.createOrderId(newUserId(), id, 3);
        JsonNode reserved = api.product(id);
        assertThat(reserved.get("stock").asInt()).isEqualTo(10);
        assertThat(reserved.get("reserved").asInt()).isEqualTo(3);
        assertThat(reserved.get("available").asInt()).isEqualTo(7);

        api.pay(orderId).assertStatus(200);
        JsonNode sold = api.product(id);
        assertThat(sold.get("stock").asInt()).isEqualTo(7);
        assertThat(sold.get("reserved").asInt()).isZero();
        assertThat(sold.get("available").asInt()).isEqualTo(7);

        api.createOrder(newUserId(), null, List.of(item(id, 2))).assertStatus(201);
        JsonNode both = api.product(id);
        assertThat(both.get("available").asInt())
                .isEqualTo(both.get("stock").asInt() - both.get("reserved").asInt())
                .isEqualTo(5);
    }

    private static Map<String, Object> body(String name, Long price, Integer stock) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("price", price);
        body.put("stock", stock);
        return body;
    }
}
