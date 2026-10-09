package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R1 상품")
class R1ProductTest extends IntegrationTestBase {

    private static Map<String, Object> product(Object name, Object price, Object stock) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (name != null) {
            m.put("name", name);
        }
        if (price != null) {
            m.put("price", price);
        }
        if (stock != null) {
            m.put("stock", stock);
        }
        return m;
    }

    @Test
    @DisplayName("R1.1 등록하면 201 + Location, 본문은 GET 과 같은 형태이고 reserved=0")
    void r1_1_create() {
        ResponseEntity<JsonNode> res = post("/api/products", product("키보드", 15000, 10));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        long id = res.getBody().get("id").asLong();
        assertThat(res.getHeaders().getLocation().toString()).endsWith("/api/products/" + id);
        JsonNode b = res.getBody();
        assertThat(b.get("name").asText()).isEqualTo("키보드");
        assertThat(b.get("price").asLong()).isEqualTo(15000);
        assertThat(b.get("stock").asInt()).isEqualTo(10);
        assertThat(b.get("reserved").asInt()).isZero();
        assertThat(b.get("available").asInt()).isEqualTo(10);
        assertThat(b.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "name", "price", "stock", "reserved", "available");
    }

    @Test
    @DisplayName("R1.1 Location 으로 조회한 본문이 등록 응답 본문과 같다")
    void r1_1_locationPointsToCreatedProduct() {
        ResponseEntity<JsonNode> res = post("/api/products", product("마우스", 5000, 3));

        ResponseEntity<JsonNode> fetched = get(res.getHeaders().getLocation().toString());

        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody()).isEqualTo(res.getBody());
    }

    @Test
    @DisplayName("R1.2 경계값(name 100자, price 1 / 10,000,000, stock 0 / 1,000,000)은 허용")
    void r1_2_boundariesAccepted() {
        assertThat(post("/api/products", product("a".repeat(100), 1, 0)).getStatusCode().value()).isEqualTo(201);
        assertThat(post("/api/products", product("x", 10_000_000, 1_000_000)).getStatusCode().value())
                .isEqualTo(201);
    }

    static Object[][] invalidProducts() {
        return new Object[][] {
                {"name 누락", product(null, 1000, 1)},
                {"name 빈 문자열", product("", 1000, 1)},
                {"name 공백만", product("   ", 1000, 1)},
                {"name 101자", product("a".repeat(101), 1000, 1)},
                {"price 누락", product("x", null, 1)},
                {"price 0", product("x", 0, 1)},
                {"price 음수", product("x", -1, 1)},
                {"price 10,000,001", product("x", 10_000_001, 1)},
                {"price 가 int 범위 초과", product("x", 5_000_000_000L, 1)},
                {"stock 누락", product("x", 1000, null)},
                {"stock -1", product("x", 1000, -1)},
                {"stock 1,000,001", product("x", 1000, 1_000_001)},
                {"price 문자열", product("x", "abc", 1)},
        };
    }

    @ParameterizedTest(name = "R1.2 {0} -> 400")
    @MethodSource("invalidProducts")
    void r1_2_invalidReturns400(String label, Map<String, Object> body) {
        ResponseEntity<JsonNode> res = post("/api/products", body);

        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 위반 요청은 상품을 만들지 않는다")
    void r1_2_invalidDoesNotPersist() {
        post("/api/products", product(" ", 0, -1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Long.class)).isZero();
    }

    @Test
    @DisplayName("R1.3 조회는 200 + {id,name,price,stock,reserved,available}")
    void r1_3_get() {
        JsonNode created = createProduct("모니터", 100000, 5);

        JsonNode b = getProduct(created.get("id").asLong());

        assertThat(b.get("id").asLong()).isEqualTo(created.get("id").asLong());
        assertThat(b.get("name").asText()).isEqualTo("모니터");
        assertThat(b.get("price").asLong()).isEqualTo(100000);
        assertThat(b.get("stock").asInt()).isEqualTo(5);
        assertThat(b.get("reserved").asInt()).isZero();
        assertThat(b.get("available").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("R1.3 없는 상품은 404 PRODUCT_NOT_FOUND")
    void r1_3_notFound() {
        assertProblem(get("/api/products/999"), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.4 available = stock - reserved (주문 생성 후 reserved 증가, stock 불변)")
    void r1_4_availableIsStockMinusReserved() {
        long id = createProduct("키보드", 1000, 10).get("id").asLong();

        orderOk("u1", null, id, 3);

        JsonNode b = getProduct(id);
        assertThat(b.get("stock").asInt()).isEqualTo(10);
        assertThat(b.get("reserved").asInt()).isEqualTo(3);
        assertThat(b.get("available").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("R1.4 결제 승인 후 stock 과 reserved 가 함께 줄고 available 은 그대로")
    void r1_4_afterPayment() {
        long id = createProduct("키보드", 1000, 10).get("id").asLong();
        long orderId = orderOk("u1", null, id, 3).get("id").asLong();

        payOk(orderId);

        JsonNode b = getProduct(id);
        assertThat(b.get("stock").asInt()).isEqualTo(7);
        assertThat(b.get("reserved").asInt()).isZero();
        assertThat(b.get("available").asInt()).isEqualTo(7);
    }
}
