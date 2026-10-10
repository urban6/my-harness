package com.example.order;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R1. 상품")
class ProductApiTest extends IntegrationTest {

    @Test
    @DisplayName("R1.1 등록하면 201, Location, reserved=0 인 본문")
    void createProduct() {
        Resp r = post("/api/products", Map.of("name", "키보드", "price", 35_000, "stock", 7));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.headers().firstValue("Location")).hasValue("/api/products/" + r.id());
        assertThat(r.json().path("name").asText()).isEqualTo("키보드");
        assertThat(r.json().path("price").asLong()).isEqualTo(35_000);
        assertThat(r.json().path("stock").asInt()).isEqualTo(7);
        assertThat(r.json().path("reserved").asInt()).isZero();
        assertThat(r.json().path("available").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("R1.3 조회하면 200 {id, name, price, stock, reserved, available}")
    void getProduct() {
        Resp created = post("/api/products", Map.of("name", "마우스", "price", 10_000_000, "stock", 1_000_000));

        Resp r = get("/api/products/" + created.id());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("id").asLong()).isEqualTo(created.id());
        assertThat(r.json().path("name").asText()).isEqualTo("마우스");
        assertThat(r.json().path("price").asLong()).isEqualTo(10_000_000);
        assertThat(r.json().path("stock").asInt()).isEqualTo(1_000_000);
        assertThat(r.json().path("reserved").asInt()).isZero();
        assertThat(r.json().path("available").asInt()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("R1.3 없는 상품은 404 PRODUCT_NOT_FOUND")
    void productNotFound() {
        assertProblem(get("/api/products/987654321"), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.2 경계값(이름 100자, price 1, stock 0)은 허용")
    void boundariesAccepted() {
        Resp r = post("/api/products", Map.of("name", "가".repeat(100), "price", 1, "stock", 0));
        assertThat(r.status()).isEqualTo(201);
    }

    static Stream<Map<String, Object>> invalidProducts() {
        return Stream.of(
                with("name", null),
                with("name", ""),
                with("name", "   "),
                with("name", "a".repeat(101)),
                with("price", null),
                with("price", 0),
                with("price", 10_000_001),
                with("price", 1.5),
                with("stock", null),
                with("stock", -1),
                with("stock", 1_000_001));
    }

    private static Map<String, Object> with(String field, Object value) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "상품", "price", 1000, "stock", 10));
        if (value == null) {
            body.remove(field);
        } else {
            body.put(field, value);
        }
        return body;
    }

    @ParameterizedTest
    @MethodSource("invalidProducts")
    @DisplayName("R1.2 검증 위반은 400 VALIDATION_ERROR")
    void invalidProduct(Map<String, Object> body) {
        assertProblem(post("/api/products", body), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.4 available = stock − reserved, 결제되면 stock 과 reserved 가 함께 줄어든다")
    void availableReflectsReservationAndSale() {
        long productId = createProduct(1_000, 10);

        JsonNode order = placeOrder(item(productId, 3));
        JsonNode reserved = product(productId);
        assertThat(reserved.path("stock").asInt()).isEqualTo(10);
        assertThat(reserved.path("reserved").asInt()).isEqualTo(3);
        assertThat(reserved.path("available").asInt()).isEqualTo(7);

        assertThat(pay(order.path("id").asLong(), "tok_ok").status()).isEqualTo(200);
        JsonNode sold = product(productId);
        assertThat(sold.path("stock").asInt()).isEqualTo(7);
        assertThat(sold.path("reserved").asInt()).isZero();
        assertThat(sold.path("available").asInt()).isEqualTo(7);
    }
}
