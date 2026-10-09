package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ProductApiTest extends ApiTestSupport {

    @Nested
    @DisplayName("R1 상품 등록")
    class CreateProduct {

        @Test
        @DisplayName("201 + Location, 본문은 상품 조회 응답과 같다")
        void createsProduct() {
            ResponseEntity<String> response = post("/api/products", """
                    {"name":"키보드","price":35000,"stock":7}
                    """);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode body = json(response);
            long id = body.get("id").asLong();
            assertThat(body.get("name").asText()).isEqualTo("키보드");
            assertThat(body.get("price").asLong()).isEqualTo(35000);
            assertThat(body.get("stock").asInt()).isEqualTo(7);
            assertThat(response.getHeaders().getLocation()).isNotNull();
            assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/api/products/" + id);

            ResponseEntity<String> fetched = get(response.getHeaders().getLocation().getPath());
            assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(fetched)).isEqualTo(body);
        }

        @Test
        @DisplayName("재고 0은 허용된다")
        void allowsZeroStock() {
            ResponseEntity<String> response = post("/api/products", """
                    {"name":"품절상품","price":1,"stock":0}
                    """);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(json(response).get("stock").asInt()).isZero();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "{\"price\":1000,\"stock\":1}",
                "{\"name\":null,\"price\":1000,\"stock\":1}",
                "{\"name\":\"\",\"price\":1000,\"stock\":1}",
                "{\"name\":\"   \",\"price\":1000,\"stock\":1}",
                "{\"name\":\"A\",\"stock\":1}",
                "{\"name\":\"A\",\"price\":0,\"stock\":1}",
                "{\"name\":\"A\",\"price\":-100,\"stock\":1}",
                "{\"name\":\"A\",\"price\":10.5,\"stock\":1}",
                "{\"name\":\"A\",\"price\":1000}",
                "{\"name\":\"A\",\"price\":1000,\"stock\":-1}",
        })
        @DisplayName("규칙 위반 시 400")
        void rejectsInvalidProduct(String body) {
            ResponseEntity<String> response = post("/api/products", body);

            assertProblem(response, HttpStatus.BAD_REQUEST);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Long.class)).isZero();
        }
    }

    @Nested
    @DisplayName("R2 상품 조회")
    class GetProduct {

        @Test
        @DisplayName("200 {id, name, price, stock}")
        void returnsProduct() {
            long id = createProduct("마우스", 12000, 3);

            ResponseEntity<String> response = get("/api/products/" + id);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = json(response);
            assertThat(body.get("id").asLong()).isEqualTo(id);
            assertThat(body.get("name").asText()).isEqualTo("마우스");
            assertThat(body.get("price").asLong()).isEqualTo(12000);
            assertThat(body.get("stock").asInt()).isEqualTo(3);
            assertThat(body.size()).isEqualTo(4);
        }

        @Test
        @DisplayName("없으면 404")
        void returns404WhenMissing() {
            assertProblem(get("/api/products/999999"), HttpStatus.NOT_FOUND);
        }
    }
}
