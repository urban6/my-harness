package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

class ProductApiTest extends IntegrationTestSupport {

    @Nested
    @DisplayName("R1 상품 등록")
    class Create {

        @Test
        @DisplayName("유효한 요청이면 201, Location 헤더, 상품 조회와 같은 형태의 본문을 반환한다")
        void createsProduct() {
            ResponseEntity<String> response = post("/api/products",
                    "{\"name\":\"Keyboard\",\"price\":35000,\"stock\":7}");

            assertThat(response.getStatusCode().value()).isEqualTo(201);
            JsonNode body = json(response);
            long id = body.get("id").asLong();
            assertThat(body.get("name").asText()).isEqualTo("Keyboard");
            assertThat(body.get("price").isIntegralNumber()).isTrue();
            assertThat(body.get("price").asLong()).isEqualTo(35000);
            assertThat(body.get("stock").asInt()).isEqualTo(7);
            assertThat(response.getHeaders().getLocation()).isNotNull();
            assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/api/products/" + id);

            ResponseEntity<String> fetched = get(response.getHeaders().getLocation().getPath());
            assertThat(fetched.getStatusCode().value()).isEqualTo(200);
            assertThat(json(fetched)).isEqualTo(body);
        }

        @Test
        @DisplayName("재고 0, 가격 1은 허용된다")
        void acceptsBoundaryValues() {
            ResponseEntity<String> response = post("/api/products", "{\"name\":\"Pen\",\"price\":1,\"stock\":0}");

            assertThat(response.getStatusCode().value()).isEqualTo(201);
            assertThat(json(response).get("stock").asInt()).isZero();
            assertThat(json(response).get("price").asLong()).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "{\"price\":1000,\"stock\":1}",
                "{\"name\":null,\"price\":1000,\"stock\":1}",
                "{\"name\":\"\",\"price\":1000,\"stock\":1}",
                "{\"name\":\"   \",\"price\":1000,\"stock\":1}",
                "{\"name\":\"A\",\"stock\":1}",
                "{\"name\":\"A\",\"price\":0,\"stock\":1}",
                "{\"name\":\"A\",\"price\":-1,\"stock\":1}",
                "{\"name\":\"A\",\"price\":10.5,\"stock\":1}",
                "{\"name\":\"A\",\"price\":1000}",
                "{\"name\":\"A\",\"price\":1000,\"stock\":-1}"
        })
        @DisplayName("name·price·stock 규칙을 위반하면 400 Problem Details")
        void rejectsInvalidRequest(String body) {
            assertProblem(post("/api/products", body), 400);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Long.class)).isZero();
        }
    }

    @Nested
    @DisplayName("R2 상품 조회")
    class Get {

        @Test
        @DisplayName("존재하는 상품이면 200 {id, name, price, stock}")
        void returnsProduct() {
            long id = createProduct("Mouse", 12000, 3);

            ResponseEntity<String> response = get("/api/products/" + id);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            JsonNode body = json(response);
            assertThat(body.get("id").asLong()).isEqualTo(id);
            assertThat(body.get("name").asText()).isEqualTo("Mouse");
            assertThat(body.get("price").asLong()).isEqualTo(12000);
            assertThat(body.get("stock").asInt()).isEqualTo(3);
        }

        @Test
        @DisplayName("없는 상품이면 404 Problem Details")
        void notFound() {
            assertProblem(get("/api/products/999999"), 404);
        }
    }
}
