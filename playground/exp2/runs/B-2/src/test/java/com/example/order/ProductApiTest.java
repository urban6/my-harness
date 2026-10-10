package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** R1. 상품 */
class ProductApiTest extends IntegrationTestSupport {

    @Test
    void create_returns201WithLocationAndBody() {
        Res res = post("/api/products", Map.of("name", "키보드", "price", 45_000, "stock", 7), Map.of());

        assertThat(res.status()).isEqualTo(201);
        long id = res.body().get("id").asLong();
        assertThat(res.headers().getLocation()).isNotNull();
        assertThat(res.headers().getLocation().getPath()).isEqualTo("/api/products/" + id);
        assertThat(res.body().get("name").asText()).isEqualTo("키보드");
        assertThat(res.body().get("price").asLong()).isEqualTo(45_000);
        assertThat(res.body().get("stock").asInt()).isEqualTo(7);
        assertThat(res.body().get("reserved").asInt()).isZero();
        assertThat(res.body().get("available").asInt()).isEqualTo(7);
    }

    @Test
    void get_returnsSameShapeAsCreate() {
        Res created = post("/api/products", Map.of("name", "마우스", "price", 1, "stock", 0), Map.of());

        Res res = get("/api/products/" + created.body().get("id").asLong());

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(created.body());
    }

    @Test
    void get_unknown_returns404() {
        assertProblem(get("/api/products/999999999"), 404, "PRODUCT_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"price\":1000,\"stock\":1}",
            "{\"name\":\"   \",\"price\":1000,\"stock\":1}",
            "{\"name\":\"%s\",\"price\":1000,\"stock\":1}",
            "{\"name\":\"a\",\"stock\":1}",
            "{\"name\":\"a\",\"price\":0,\"stock\":1}",
            "{\"name\":\"a\",\"price\":10000001,\"stock\":1}",
            "{\"name\":\"a\",\"price\":1.5,\"stock\":1}",
            "{\"name\":\"a\",\"price\":\"1000\",\"stock\":1}",
            "{\"name\":\"a\",\"price\":1000}",
            "{\"name\":\"a\",\"price\":1000,\"stock\":-1}",
            "{\"name\":\"a\",\"price\":1000,\"stock\":1000001}",
            "{\"name\":\"a\",\"price\":1000,\"stock\":99999999999}",
    })
    void create_invalid_returns400(String json) {
        Res res = post("/api/products", json.formatted("a".repeat(101)), Map.of());

        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    void create_acceptsBoundaryValues() {
        Res max = post("/api/products", Map.of("name", "a".repeat(100), "price", 10_000_000, "stock", 1_000_000), Map.of());
        Res min = post("/api/products", Map.of("name", "a", "price", 1, "stock", 0), Map.of());

        assertThat(max.status()).isEqualTo(201);
        assertThat(min.status()).isEqualTo(201);
    }

    @Test
    void reservedAndAvailable_followOrderLifecycle() {
        long productId = createProduct(1_000, 5);

        long orderId = placeOrder(uniqueUser(), null, item(productId, 2));
        assertThat(product(productId).get("stock").asInt()).isEqualTo(5);
        assertThat(product(productId).get("reserved").asInt()).isEqualTo(2);
        assertThat(product(productId).get("available").asInt()).isEqualTo(3);

        assertThat(pay(orderId, uniqueKey()).status()).isEqualTo(200);
        assertThat(product(productId).get("stock").asInt()).isEqualTo(3);
        assertThat(product(productId).get("reserved").asInt()).isZero();
        assertThat(product(productId).get("available").asInt()).isEqualTo(3);
    }
}
