package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 구현이 실제로 도는지 확인하는 happy path 스모크. 요구사항 전량 검증은 Phase 3 테스트가 맡는다. */
class SmokeTest extends IntegrationTestBase {

    @Test
    void flywayMigrationIsApplied() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and version = '1'", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void registerAndGetProduct() {
        ResponseEntity<JsonNode> created = post("/api/products", Map.of("name", "Keyboard", "price", 15000, "stock", 10));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long id = created.getBody().get("id").asLong();
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getHeaders().getLocation().getPath()).isEqualTo("/api/products/" + id);
        assertThat(created.getBody().get("reserved").asInt()).isZero();
        assertThat(created.getBody().get("available").asInt()).isEqualTo(10);

        ResponseEntity<JsonNode> fetched = get("/api/products/" + id);
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody().get("name").asText()).isEqualTo("Keyboard");
        assertThat(fetched.getBody().get("price").asLong()).isEqualTo(15000);
    }

    @Test
    void orderThenPayApproved() {
        long productId = createProduct("Mouse", 20000, 5).get("id").asLong();

        ResponseEntity<JsonNode> order = post("/api/orders",
                Map.of("items", List.of(Map.of("productId", productId, "quantity", 2))),
                "X-User-Id", "u1", "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(order.getStatusCode().value()).isEqualTo(201);
        assertThat(order.getBody().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.getBody().get("totalPrice").asLong()).isEqualTo(40000);
        assertThat(order.getBody().get("paidAt").isNull()).isTrue();
        long orderId = order.getBody().get("id").asLong();
        assertThat(get("/api/products/" + productId).getBody().get("reserved").asInt()).isEqualTo(2);

        String payKey = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> paid = post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok_ok"),
                "Idempotency-Key", payKey);
        assertThat(paid.getStatusCode().value()).isEqualTo(200);
        assertThat(paid.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(paid.getBody().get("paidAt").isNull()).isFalse();
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
        assertThat(PG.requests().get(0).header("Idempotency-Key")).isEqualTo(payKey);

        JsonNode product = get("/api/products/" + productId).getBody();
        assertThat(product.get("stock").asInt()).isEqualTo(3);
        assertThat(product.get("reserved").asInt()).isZero();

        // 같은 키 재요청은 PG 를 다시 호출하지 않고 최초 응답을 재생한다.
        ResponseEntity<JsonNode> replay = post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "tok_ok"),
                "Idempotency-Key", payKey);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(paid.getBody());
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
    }

    @Test
    void validationErrorIsProblemJsonAndJsonIsStrict() {
        ResponseEntity<JsonNode> blank = post("/api/products", Map.of("name", " ", "price", 100, "stock", 1));
        assertThat(blank.getStatusCode().value()).isEqualTo(400);
        assertThat(blank.getHeaders().getContentType().toString()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(blank.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(blank.getBody().get("status").asInt()).isEqualTo(400);
        assertThat(blank.getBody().hasNonNull("type")).isTrue();
        assertThat(blank.getBody().hasNonNull("title")).isTrue();
        assertThat(blank.getBody().hasNonNull("detail")).isTrue();

        ResponseEntity<JsonNode> stringPrice = exchange(org.springframework.http.HttpMethod.POST, "/api/products",
                "{\"name\":\"x\",\"price\":\"10\",\"stock\":1}");
        assertThat(stringPrice.getStatusCode().value()).isEqualTo(400);
        ResponseEntity<JsonNode> floatStock = exchange(org.springframework.http.HttpMethod.POST, "/api/products",
                "{\"name\":\"x\",\"price\":10,\"stock\":1.5}");
        assertThat(floatStock.getStatusCode().value()).isEqualTo(400);
        ResponseEntity<JsonNode> broken = exchange(org.springframework.http.HttpMethod.POST, "/api/products",
                "{\"name\":");
        assertThat(broken.getStatusCode().value()).isEqualTo(400);
        assertThat(broken.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");

        assertThat(get("/api/products/999999").getBody().get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(get("/api/products/abc").getStatusCode().value()).isEqualTo(400);
    }
}
