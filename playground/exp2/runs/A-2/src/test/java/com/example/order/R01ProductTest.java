package com.example.order;

import com.example.order.support.Api.Resp;
import com.example.order.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R1 상품")
class R01ProductTest extends IntegrationTest {

    @Test
    @DisplayName("R1.1 등록하면 201 + Location, 본문은 조회와 같은 형태이고 reserved는 0")
    void createProduct() {
        Resp resp = api.post("/api/products", Map.of("name", "키보드", "price", 35000, "stock", 7));

        assertThat(resp.status()).isEqualTo(201);
        assertThat(resp.header("Location")).isEqualTo("/api/products/" + resp.id());
        assertThat(resp.json().path("name").asText()).isEqualTo("키보드");
        assertThat(resp.json().path("price").asLong()).isEqualTo(35000);
        assertThat(resp.json().path("stock").asInt()).isEqualTo(7);
        assertThat(resp.json().path("reserved").asInt()).isZero();
        assertThat(resp.json().path("available").asInt()).isEqualTo(7);
        assertThat(api.get(resp.header("Location")).json()).isEqualTo(resp.json());
    }

    @Test
    @DisplayName("R1.2 경계값(이름 100자, 가격 10,000,000, 재고 0·1,000,000)은 허용")
    void boundariesAccepted() {
        assertThat(api.post("/api/products", Map.of("name", "a".repeat(100), "price", 10_000_000, "stock", 1_000_000))
                .status()).isEqualTo(201);
        assertThat(api.post("/api/products", Map.of("name", "x", "price", 1, "stock", 0)).status()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"price\":1000,\"stock\":1}",
            "{\"name\":\"   \",\"price\":1000,\"stock\":1}",
            "{\"name\":\"" + "aaaaaaaaaa" + "aaaaaaaaaa" + "aaaaaaaaaa" + "aaaaaaaaaa" + "aaaaaaaaaa"
                    + "aaaaaaaaaa" + "aaaaaaaaaa" + "aaaaaaaaaa" + "aaaaaaaaaa" + "aaaaaaaaaa" + "a\",\"price\":1000,\"stock\":1}",
            "{\"name\":\"n\",\"stock\":1}",
            "{\"name\":\"n\",\"price\":0,\"stock\":1}",
            "{\"name\":\"n\",\"price\":10000001,\"stock\":1}",
            "{\"name\":\"n\",\"price\":1000}",
            "{\"name\":\"n\",\"price\":1000,\"stock\":-1}",
            "{\"name\":\"n\",\"price\":1000,\"stock\":1000001}",
            "{\"name\":\"n\",\"price\":10.5,\"stock\":1}",
            "{\"name\":\"n\",\"price\":\"1000\",\"stock\":1}"
    })
    @DisplayName("R1.2 규칙을 어기면 400")
    void invalidProduct(String body) {
        assertProblem(api.postRaw("/api/products", body), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.3 없는 상품 조회는 404")
    void productNotFound() {
        assertProblem(api.get("/api/products/999999"), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.4 available = stock - reserved, 결제되면 stock과 reserved가 함께 줄어든다")
    void availableIsStockMinusReserved() {
        long productId = createProduct(1000, 10);
        long orderId = placeOrderOk("u1", null, productId, 3);

        assertThat(product(productId).path("stock").asInt()).isEqualTo(10);
        assertThat(product(productId).path("reserved").asInt()).isEqualTo(3);
        assertThat(product(productId).path("available").asInt()).isEqualTo(7);

        assertThat(pay(orderId).status()).isEqualTo(200);
        assertThat(product(productId).path("stock").asInt()).isEqualTo(7);
        assertThat(product(productId).path("reserved").asInt()).isZero();
        assertThat(product(productId).path("available").asInt()).isEqualTo(7);
    }
}
