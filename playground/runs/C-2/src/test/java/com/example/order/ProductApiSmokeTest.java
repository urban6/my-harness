package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ProductApiSmokeTest extends AbstractApiTest {

    @Test
    void createProduct_returns201WithLocation_andGetReturnsSameShape() {
        ResponseEntity<JsonNode> created =
                post("/api/products", Map.of("name", "키보드", "price", 35000, "stock", 10));

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long id = created.getBody().get("id").asLong();
        assertThat(locationOf(created).getPath()).isEqualTo("/api/products/" + id);

        ResponseEntity<JsonNode> fetched = get("/api/products/" + id);
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody()).isEqualTo(created.getBody());
        assertThat(fetched.getBody().get("name").asText()).isEqualTo("키보드");
        assertThat(fetched.getBody().get("price").asLong()).isEqualTo(35000);
        assertThat(fetched.getBody().get("stock").asInt()).isEqualTo(10);
    }

    @Test
    void getProduct_unknownId_returns404Problem() {
        assertProblem(get("/api/products/999"), 404, "product-not-found");
    }

    @Test
    void createProduct_blankName_returns400ValidationProblem() {
        ResponseEntity<JsonNode> res = post("/api/products", Map.of("name", "  ", "price", 100, "stock", 1));
        assertProblem(res, 400, "validation-error");
        assertThat(res.getBody().get("errors").get(0).get("field").asText()).isEqualTo("name");
    }

    @Test
    void createProduct_brokenJson_returns400MalformedProblem() {
        assertProblem(postRaw("/api/products", "{not json"), 400, "malformed-request");
    }
}
