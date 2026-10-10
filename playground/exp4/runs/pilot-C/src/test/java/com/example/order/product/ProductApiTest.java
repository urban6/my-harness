package com.example.order.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

/** R1. 상품 등록·조회. */
class ProductApiTest extends IntegrationTestBase {

    private ResponseEntity<JsonNode> register(Object name, Object price, Object stock) {
        Map<String, Object> body = map("name", name, "price", price, "stock", stock);
        body.values().removeIf(v -> v == null);
        return post("/api/products", body);
    }

    @Test
    @DisplayName("R1.1 상품을 등록하면 201 + Location + reserved=0 본문을 돌려준다")
    void r1_1_registerReturns201WithLocationAndBody() {
        ResponseEntity<JsonNode> res = register("Keyboard", 15000, 10);

        assertStatus(res, 201);
        long id = res.getBody().get("id").asLong();
        assertThat(res.getHeaders().getLocation()).isNotNull();
        assertThat(res.getHeaders().getLocation().getPath()).isEqualTo("/api/products/" + id);
        assertThat(res.getBody().get("name").asText()).isEqualTo("Keyboard");
        assertThat(res.getBody().get("price").asLong()).isEqualTo(15000);
        assertThat(res.getBody().get("stock").asInt()).isEqualTo(10);
        assertThat(res.getBody().get("reserved").asInt()).isZero();
        assertThat(res.getBody().get("available").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R1.1 등록 응답 본문은 GET 조회(R1.3)와 같다")
    void r1_1_registerBodyEqualsGetBody() {
        ResponseEntity<JsonNode> created = register("Same", 1000, 3);

        assertThat(get("/api/products/" + created.getBody().get("id").asLong()).getBody()).isEqualTo(created.getBody());
    }

    @Test
    @DisplayName("R1.2 name 100자는 201, 한글 100자도 201")
    void r1_2_name100CharsAccepted() {
        assertStatus(register("a".repeat(100), 100, 1), 201);
        assertStatus(register("가".repeat(100), 100, 1), 201);
    }

    @Test
    @DisplayName("R1.2 name 101자는 400")
    void r1_2_name101CharsRejected() {
        assertProblem(register("a".repeat(101), 100, 1), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 name 이 JSON 숫자·불리언이면 String 으로 변환하지 않고 400")
    void r1_2_nonStringNameRejected() {
        for (String raw : new String[] {"{\"name\":123,\"price\":100,\"stock\":1}",
                "{\"name\":true,\"price\":100,\"stock\":1}", "{\"name\":1.5,\"price\":100,\"stock\":1}"}) {
            assertProblem(post("/api/products", raw), 400, "VALIDATION_ERROR");
        }
    }

    @ParameterizedTest(name = "R1.2 name=\"{0}\" 은 400")
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void r1_2_blankNameRejected(String name) {
        assertProblem(register(name, 100, 1), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 name 누락·null 은 400")
    void r1_2_missingNameRejected() {
        assertProblem(register(null, 100, 1), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/products", "{\"name\":null,\"price\":100,\"stock\":1}"), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "R1.2 price={0} 은 400")
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 10_000_001L, 2_147_483_648L, Long.MAX_VALUE})
    void r1_2_priceOutOfRangeRejected(long price) {
        assertProblem(register("p", price, 1), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "R1.2 price={0} 은 201 (경계)")
    @ValueSource(longs = {1, 2, 10_000_000L})
    void r1_2_priceBoundariesAccepted(long price) {
        ResponseEntity<JsonNode> res = register("p", price, 1);

        assertStatus(res, 201);
        assertThat(res.getBody().get("price").asLong()).isEqualTo(price);
    }

    @Test
    @DisplayName("R1.2 price 누락은 400")
    void r1_2_missingPriceRejected() {
        assertProblem(register("p", null, 1), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "R1.2 stock={0} 은 400")
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 1_000_001, Integer.MAX_VALUE})
    void r1_2_stockOutOfRangeRejected(int stock) {
        assertProblem(register("p", 100, stock), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "R1.2 stock={0} 은 201 (경계)")
    @ValueSource(ints = {0, 1, 1_000_000})
    void r1_2_stockBoundariesAccepted(int stock) {
        ResponseEntity<JsonNode> res = register("p", 100, stock);

        assertStatus(res, 201);
        assertThat(res.getBody().get("stock").asInt()).isEqualTo(stock);
        assertThat(res.getBody().get("available").asInt()).isEqualTo(stock);
    }

    @Test
    @DisplayName("R1.2 stock 누락은 400")
    void r1_2_missingStockRejected() {
        assertProblem(register("p", 100, null), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 검증 실패한 요청은 상품을 만들지 않는다")
    void r1_2_rejectedRequestCreatesNothing() {
        String name = "never-created-" + uniqueKey();

        assertStatus(register(name, 0, 1), 400);

        assertThat(jdbc.queryForObject("select count(*) from products where name = ?", Integer.class, name)).isZero();
    }

    @Test
    @DisplayName("R1.3 GET 은 {id,name,price,stock,reserved,available} 를 담은 200 을 돌려준다")
    void r1_3_getReturnsDocumentedShape() {
        long id = newProduct(2500, 7);

        ResponseEntity<JsonNode> res = get("/api/products/" + id);

        assertStatus(res, 200);
        List<String> fields = new ArrayList<>();
        res.getBody().fieldNames().forEachRemaining(fields::add);
        assertThat(fields).contains("id", "name", "price", "stock", "reserved", "available");
        assertThat(res.getBody().get("id").asLong()).isEqualTo(id);
    }

    @Test
    @DisplayName("R1.3 없는 상품 조회는 404 PRODUCT_NOT_FOUND")
    void r1_3_unknownProductReturns404() {
        assertProblem(get("/api/products/999999999"), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.4 주문 생성 시 reserved 가 늘고 available = stock - reserved 이다")
    void r1_4_availableIsStockMinusReserved() {
        long id = newProduct(1000, 10);

        newOrder(id, 3);

        JsonNode p = product(id);
        assertThat(p.get("stock").asInt()).isEqualTo(10);
        assertThat(p.get("reserved").asInt()).isEqualTo(3);
        assertThat(p.get("available").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("R1.4 결제 승인 후에는 stock 과 reserved 가 모두 줄어 available 은 그대로다")
    void r1_4_paidOrderDecreasesStockAndReserved() {
        long id = newProduct(1000, 10);
        long orderId = newOrder(id, 3).get("id").asLong();

        payOk(orderId);

        assertStock(id, 7, 0);
    }

    @Test
    @DisplayName("C1 price 상한(10,000,000)은 long 으로 그대로 돌려준다")
    void c1_priceIsReturnedAsLong() {
        long id = newProduct(10_000_000L, 1);

        assertThat(product(id).get("price").asLong()).isEqualTo(10_000_000L);
    }
}
