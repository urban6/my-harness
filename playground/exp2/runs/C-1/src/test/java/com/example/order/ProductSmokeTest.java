package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class ProductSmokeTest extends IntegrationTestBase {

    @Test
    void createAndGetProduct() {
        ResponseEntity<JsonNode> created = post("/api/products", Map.of("name", "키보드", "price", 15000, "stock", 10));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getBody().get("reserved").asInt()).isZero();
        assertThat(created.getBody().get("available").asInt()).isEqualTo(10);

        ResponseEntity<JsonNode> fetched = get(created.getHeaders().getLocation().toString());
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("name").asText()).isEqualTo("키보드");
        assertThat(fetched.getBody().get("price").asLong()).isEqualTo(15000);
    }

    @Test
    void invalidProductReturnsProblemDetail400() {
        ResponseEntity<JsonNode> res = post("/api/products", Map.of("name", " ", "price", 0, "stock", -1));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(res.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void unknownProductReturns404() {
        ResponseEntity<JsonNode> res = get("/api/products/999");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }
}
