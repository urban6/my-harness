package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R1. 상품 */
class ProductApiTest extends IntegrationTestBase {

    private Map<String, Object> body(Object name, Object price, Object stock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("price", price);
        m.put("stock", stock);
        return m;
    }

    @Test
    @DisplayName("R1.1 등록하면 201 + Location + 본문(reserved=0)")
    void createReturns201WithLocation() {
        Res r = post("/api/products", body("키보드", 35000, 10));

        assertThat(r.status()).isEqualTo(201);
        JsonNode json = r.json();
        assertThat(json.get("name").asText()).isEqualTo("키보드");
        assertThat(json.get("price").asLong()).isEqualTo(35000);
        assertThat(json.get("stock").asLong()).isEqualTo(10);
        assertThat(json.get("reserved").asLong()).isZero();
        assertThat(json.get("available").asLong()).isEqualTo(10);
        assertThat(r.header("Location")).endsWith("/api/products/" + json.get("id").asLong());
        assertThat(get(r.header("Location").replaceFirst("^https?://[^/]+", "")).json()).isEqualTo(json);
    }

    @Test
    @DisplayName("R1.2 경계값: price 1·10,000,000, stock 0·1,000,000, name 100자는 허용")
    void boundariesAccepted() {
        assertThat(post("/api/products", body("a", 1, 0)).status()).isEqualTo(201);
        assertThat(post("/api/products", body("a".repeat(100), 10_000_000, 1_000_000)).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R1.2 경계 밖 값과 형식 오류는 400")
    void invalidBodiesRejected() {
        Object[][] cases = {
                {null, 100, 1},
                {"   ", 100, 1},
                {"", 100, 1},
                {"a".repeat(101), 100, 1},
                {"x", 0, 1},
                {"x", -5, 1},
                {"x", 10_000_001, 1},
                {"x", null, 1},
                {"x", 100, -1},
                {"x", 100, 1_000_001},
                {"x", 100, null},
                {"x", "100", 1},
                {"x", 100.5, 1},
                {"x", 100, "many"},
                {1234, 100, 1},
        };
        for (Object[] c : cases) {
            Res r = post("/api/products", body(c[0], c[1], c[2]));
            assertThat(r.status()).as("case %s/%s/%s", c[0], c[1], c[2]).isEqualTo(400);
            assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    @DisplayName("R1.2 본문이 없거나 JSON이 아니면 400")
    void malformedBody() {
        assertThat(post("/api/products", "{not json").status()).isEqualTo(400);
        assertThat(post("/api/products", "").status()).isEqualTo(400);
        assertThat(post("/api/products", "[]").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R1.3 조회: 존재하지 않으면 404 PRODUCT_NOT_FOUND")
    void getMissing() {
        Res r = get("/api/products/999999999");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(get("/api/products/abc").code()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.4 reserved = 결제 대기 주문 수량, available = stock - reserved")
    void reservedAndAvailable() {
        long id = product(1000, 10);
        order(id, 3);
        order(id, 2);

        JsonNode p = productOf(id);
        assertThat(p.get("stock").asLong()).isEqualTo(10);
        assertThat(p.get("reserved").asLong()).isEqualTo(5);
        assertThat(p.get("available").asLong()).isEqualTo(5);
    }
}
