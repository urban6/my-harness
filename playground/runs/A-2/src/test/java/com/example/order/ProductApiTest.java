package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ProductApiTest extends IntegrationTestSupport {

    @Nested
    @DisplayName("R1 상품 등록")
    class Create {

        @Test
        void createsProductWithLocationAndBody() {
            ResponseEntity<String> response = post("/api/products",
                    Map.of("name", "Keyboard", "price", 30000, "stock", 5));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode body = json(response);
            long id = body.get("id").asLong();
            assertThat(response.getHeaders().getLocation()).isNotNull();
            assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/api/products/" + id);
            assertThat(body.get("name").asText()).isEqualTo("Keyboard");
            assertThat(body.get("price").asLong()).isEqualTo(30000);
            assertThat(body.get("stock").asInt()).isEqualTo(5);

            // The body has the same shape as R2.
            assertThat(json(get("/api/products/" + id))).isEqualTo(body);
        }

        @Test
        void allowsZeroStock() {
            ResponseEntity<String> response = post("/api/products", Map.of("name", "Mouse", "price", 1, "stock", 0));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(json(response).get("stock").asInt()).isZero();
        }

        @Test
        void rejectsMissingName() {
            assertProblem(post("/api/products", Map.of("price", 1000, "stock", 1)), HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsEmptyOrBlankName() {
            assertProblem(post("/api/products", Map.of("name", "", "price", 1000, "stock", 1)),
                    HttpStatus.BAD_REQUEST);
            assertProblem(post("/api/products", Map.of("name", "   ", "price", 1000, "stock", 1)),
                    HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsNullName() {
            Map<String, Object> body = new HashMap<>();
            body.put("name", null);
            body.put("price", 1000);
            body.put("stock", 1);
            assertProblem(post("/api/products", body), HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsNonPositivePrice() {
            assertProblem(post("/api/products", Map.of("name", "A", "price", 0, "stock", 1)), HttpStatus.BAD_REQUEST);
            assertProblem(post("/api/products", Map.of("name", "A", "price", -1, "stock", 1)), HttpStatus.BAD_REQUEST);
            assertProblem(post("/api/products", Map.of("name", "A", "stock", 1)), HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsNonIntegerPrice() {
            assertProblem(post("/api/products", "{\"name\":\"A\",\"price\":10.5,\"stock\":1}"),
                    HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsNegativeOrMissingStock() {
            assertProblem(post("/api/products", Map.of("name", "A", "price", 100, "stock", -1)),
                    HttpStatus.BAD_REQUEST);
            assertProblem(post("/api/products", Map.of("name", "A", "price", 100)), HttpStatus.BAD_REQUEST);
        }

        @Test
        void doesNotPersistInvalidProduct() {
            post("/api/products", Map.of("name", " ", "price", 100, "stock", 1));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Long.class)).isZero();
        }
    }

    @Nested
    @DisplayName("R2 상품 조회")
    class Get {

        @Test
        void returnsProduct() {
            long id = createProduct("Monitor", 250000, 3);

            ResponseEntity<String> response = get("/api/products/" + id);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = json(response);
            assertThat(body.get("id").asLong()).isEqualTo(id);
            assertThat(body.get("name").asText()).isEqualTo("Monitor");
            assertThat(body.get("price").asLong()).isEqualTo(250000);
            assertThat(body.get("stock").asInt()).isEqualTo(3);
            assertThat(body.size()).isEqualTo(4);
        }

        @Test
        void returns404WhenMissing() {
            assertProblem(get("/api/products/999999"), HttpStatus.NOT_FOUND);
        }
    }
}
