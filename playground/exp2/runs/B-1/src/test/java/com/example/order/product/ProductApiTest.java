package com.example.order.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("R1 상품")
class ProductApiTest extends IntegrationTest {

    @Test
    @DisplayName("R1.1 등록하면 201 + Location, 본문은 조회 형태이고 reserved 는 0")
    void create_returns201WithLocation() {
        ApiResponse res = api.post("/api/products", Map.of("name", "키보드", "price", 59_000, "stock", 30));

        assertThat(res.status()).isEqualTo(201);
        long id = res.id();
        assertThat(res.header("Location")).endsWith("/api/products/" + id);
        assertThat(res.body().get("name").asText()).isEqualTo("키보드");
        assertThat(res.body().get("price").asLong()).isEqualTo(59_000);
        assertThat(res.body().get("stock").asInt()).isEqualTo(30);
        assertThat(res.body().get("reserved").asInt()).isZero();
        assertThat(res.body().get("available").asInt()).isEqualTo(30);
    }

    @Test
    @DisplayName("R1.3 조회는 {id, name, price, stock, reserved, available}")
    void get_returnsProduct() {
        ApiResponse created = api.post("/api/products", Map.of("name", "마우스", "price", 20_000, "stock", 5));

        ApiResponse res = api.get("/api/products/" + created.id());

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(created.body());
        assertThat(res.body().properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "name", "price", "stock", "reserved", "available");
    }

    @Test
    @DisplayName("R1.3 없는 상품은 404 PRODUCT_NOT_FOUND")
    void get_returns404WhenMissing() {
        assertProblem(api.get("/api/products/999999999"), 404, "PRODUCT_NOT_FOUND");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"price\":1000,\"stock\":1}",
            "{\"name\":\"   \",\"price\":1000,\"stock\":1}",
            "{\"name\":\"" + "x" + "\",\"price\":0,\"stock\":1}",
            "{\"name\":\"x\",\"price\":10000001,\"stock\":1}",
            "{\"name\":\"x\",\"stock\":1}",
            "{\"name\":\"x\",\"price\":1000,\"stock\":-1}",
            "{\"name\":\"x\",\"price\":1000,\"stock\":1000001}",
            "{\"name\":\"x\",\"price\":1000}",
            "{\"name\":\"x\",\"price\":\"1000\",\"stock\":1}",
            "{\"name\":\"x\",\"price\":1000.5,\"stock\":1}",
    })
    @DisplayName("R1.2 name·price·stock 규칙 위반은 400")
    void create_rejectsInvalid(String body) {
        assertProblem(api.postRaw("/api/products", body), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 name 100자 초과는 400")
    void create_rejectsTooLongName() {
        ApiResponse res = api.post("/api/products", Map.of("name", "x".repeat(101), "price", 1, "stock", 0));
        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 경계값(name 100자, price 1·10,000,000, stock 0·1,000,000)은 허용")
    void create_acceptsBoundaries() {
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("name", "x".repeat(100), "price", 1, "stock", 0),
                Map.of("name", "y", "price", 10_000_000, "stock", 1_000_000))) {
            assertThat(api.post("/api/products", body).status()).isEqualTo(201);
        }
    }

    @Test
    @DisplayName("R1.4 available = stock − reserved; 예약은 reserved, 결제는 stock 을 줄인다")
    void availableTracksReservationAndSale() {
        long productId = createProduct(1_000, 10);

        long orderId = createOrder(uniqueUser(), null, List.of(item(productId, 3)));
        assertProduct(productId, 10, 3);

        payOk(orderId);
        assertProduct(productId, 7, 0);
    }
}
