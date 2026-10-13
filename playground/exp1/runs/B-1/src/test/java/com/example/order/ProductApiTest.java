package com.example.order;

import java.util.HashMap;
import java.util.Map;

import com.example.order.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ProductApiTest extends ApiTestSupport {

    @Nested
    @DisplayName("R1 상품 등록")
    class Create {

        @Test
        void create_returns201WithLocation_andBodyMatchesGet() {
            ResponseEntity<JsonNode> res = post("/api/products", Map.of("name", "키보드", "price", 30000, "stock", 5));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode body = res.getBody();
            long id = body.get("id").asLong();
            assertThat(body.get("name").asText()).isEqualTo("키보드");
            assertThat(body.get("price").isIntegralNumber()).isTrue();
            assertThat(body.get("price").asLong()).isEqualTo(30000);
            assertThat(body.get("stock").asInt()).isEqualTo(5);
            assertThat(res.getHeaders().getLocation()).isNotNull();
            assertThat(res.getHeaders().getLocation().getPath()).isEqualTo("/api/products/" + id);

            ResponseEntity<JsonNode> fetched = get(res.getHeaders().getLocation().getPath());
            assertThat(fetched.getBody()).isEqualTo(body);
        }

        @Test
        void create_allowsZeroStock() {
            ResponseEntity<JsonNode> res = post("/api/products", Map.of("name", "품절상품", "price", 1, "stock", 0));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(res.getBody().get("stock").asInt()).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t"})
        void create_returns400_whenNameBlank(String name) {
            assertProblem(post("/api/products", Map.of("name", name, "price", 100, "stock", 1)), HttpStatus.BAD_REQUEST);
        }

        @Test
        void create_returns400_whenNameMissing() {
            assertProblem(post("/api/products", Map.of("price", 100, "stock", 1)), HttpStatus.BAD_REQUEST);
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -1})
        void create_returns400_whenPriceNotPositive(long price) {
            assertProblem(post("/api/products", Map.of("name", "a", "price", price, "stock", 1)), HttpStatus.BAD_REQUEST);
        }

        @Test
        void create_returns400_whenStockNegative() {
            assertProblem(post("/api/products", Map.of("name", "a", "price", 100, "stock", -1)), HttpStatus.BAD_REQUEST);
        }

        @Test
        void create_returns400_whenPriceOrStockMissing() {
            Map<String, Object> noPrice = new HashMap<>(Map.of("name", "a", "stock", 1));
            Map<String, Object> noStock = new HashMap<>(Map.of("name", "a", "price", 100));
            assertProblem(post("/api/products", noPrice), HttpStatus.BAD_REQUEST);
            assertProblem(post("/api/products", noStock), HttpStatus.BAD_REQUEST);
        }

        @Test
        void create_returns400_whenPriceNotInteger() {
            assertProblem(postRaw("/api/products", "{\"name\":\"a\",\"price\":10.5,\"stock\":1}"), HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("R2 상품 조회")
    class Get {

        @Test
        void get_returns200WithProduct() {
            long id = createProduct("마우스", 15000, 7);

            ResponseEntity<JsonNode> res = get("/api/products/" + id);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = res.getBody();
            assertThat(body.get("id").asLong()).isEqualTo(id);
            assertThat(body.get("name").asText()).isEqualTo("마우스");
            assertThat(body.get("price").asLong()).isEqualTo(15000);
            assertThat(body.get("stock").asInt()).isEqualTo(7);
            assertThat(body.size()).isEqualTo(4);
        }

        @Test
        void get_returns404_whenProductMissing() {
            assertProblem(get("/api/products/999999"), HttpStatus.NOT_FOUND);
        }
    }
}
