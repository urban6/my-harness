package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api;
import com.example.order.support.Api.Response;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("R1 상품")
class ProductApiTest extends IntegrationTest {

    @Test
    @DisplayName("R1.1/R1.3 등록하면 201 + Location, 조회 결과와 같은 본문 (reserved=0)")
    void createAndGet() {
        Response created = api.post("/api/products", "{\"name\":\"키보드\",\"price\":35000,\"stock\":7}");

        assertThat(created.status()).isEqualTo(201);
        long id = created.body().get("id").asLong();
        assertThat(created.location()).endsWith("/api/products/" + id);
        assertThat(created.body().get("name").asText()).isEqualTo("키보드");
        assertThat(created.body().get("price").asLong()).isEqualTo(35000);
        assertThat(created.body().get("stock").asLong()).isEqualTo(7);
        assertThat(created.body().get("reserved").asLong()).isZero();
        assertThat(created.body().get("available").asLong()).isEqualTo(7);

        Response fetched = api.get(created.location().replaceFirst("^https?://[^/]+", ""));
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(created.body());
    }

    @Test
    @DisplayName("R1.3 없는 상품은 404 PRODUCT_NOT_FOUND")
    void getMissing() {
        assertProblem(api.get("/api/products/987654321"), 404, "PRODUCT_NOT_FOUND");
    }

    static Object[][] invalidProducts() {
        return new Object[][]{
                {null, 1000, 1},
                {"   ", 1000, 1},
                {"", 1000, 1},
                {"x".repeat(101), 1000, 1},
                {"p", 0, 1},
                {"p", 10_000_001, 1},
                {"p", null, 1},
                {"p", 1000, -1},
                {"p", 1000, 1_000_001},
                {"p", 1000, null},
                {"p", 1.5, 1},
        };
    }

    @ParameterizedTest
    @MethodSource("invalidProducts")
    @DisplayName("R1.2 name·price·stock 규칙 위반은 400")
    void rejectInvalid(String name, Object price, Object stock) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("price", price);
        body.put("stock", stock);
        assertProblem(api.post("/api/products", Api.json(body)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 경계값은 허용된다")
    void acceptBoundaries() {
        assertThat(api.post("/api/products",
                Api.json(Map.of("name", "x".repeat(100), "price", 1, "stock", 0))).status()).isEqualTo(201);
        assertThat(api.post("/api/products",
                Api.json(Map.of("name", "y", "price", 10_000_000, "stock", 1_000_000))).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R1.4 available = stock - reserved, 결제되면 stock과 reserved가 함께 준다")
    void stockReservedAvailable() {
        long productId = createProduct(1000, 10);

        long orderId = createOrder(uniqueUser(), null, productId, 3).get("id").asLong();
        JsonNode reserved = product(productId);
        assertThat(reserved.get("stock").asLong()).isEqualTo(10);
        assertThat(reserved.get("reserved").asLong()).isEqualTo(3);
        assertThat(reserved.get("available").asLong()).isEqualTo(7);

        assertThat(pay(orderId, uniqueKey(), "tok").status()).isEqualTo(200);
        JsonNode sold = product(productId);
        assertThat(sold.get("stock").asLong()).isEqualTo(7);
        assertThat(sold.get("reserved").asLong()).isZero();
        assertThat(sold.get("available").asLong()).isEqualTo(7);
    }
}
