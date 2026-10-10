package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R1. 상품")
class R1ProductTest extends IntegrationTestBase {

    private Map<String, Object> body(Object name, Object price, Object stock) {
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

    // ---------------------------------------------------------------- R1.1

    @Test
    @DisplayName("R1.1 상품을 등록하면 201 과 Location 을 돌려준다")
    void r1_1_create_returns201WithLocation() {
        ResponseEntity<String> r = post("/api/products", body("노트북", 1_500_000, 7));

        assertThat(statusOf(r)).isEqualTo(201);
        long id = json(r).get("id").asLong();
        assertThat(r.getHeaders().getLocation()).hasToString("/api/products/" + id);
    }

    @Test
    @DisplayName("R1.1 등록 응답 본문은 조회와 같은 형태이고 reserved 는 0 이다")
    void r1_1_create_bodyHasSameShapeAsGet_withReservedZero() {
        ResponseEntity<String> r = post("/api/products", body("마우스", 30_000, 12));

        JsonNode created = json(r);
        assertThat(created.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "name", "price", "stock", "reserved", "available");
        assertThat(created.get("name").asText()).isEqualTo("마우스");
        assertThat(created.get("price").asLong()).isEqualTo(30_000L);
        assertThat(created.get("stock").asInt()).isEqualTo(12);
        assertThat(created.get("reserved").asInt()).isZero();
        assertThat(created.get("available").asInt()).isEqualTo(12);
        assertThat(json(getProduct(created.get("id").asLong()))).isEqualTo(created);
    }

    // ---------------------------------------------------------------- R1.2

    @ParameterizedTest(name = "[{index}] name 길이 {0} 이면 201")
    @CsvSource({"1", "100"})
    @DisplayName("R1.2 name 경계값(1자, 100자)은 허용된다")
    void r1_2_name_withinLimit_returns201(int length) {
        ResponseEntity<String> r = post("/api/products", body("a".repeat(length), 1000, 1));

        assertThat(statusOf(r)).isEqualTo(201);
    }

    @Test
    @DisplayName("R1.2 name 이 101자이면 400 VALIDATION_ERROR")
    void r1_2_name_101chars_returns400() {
        assertProblem(post("/api/products", body("a".repeat(101), 1000, 1)), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "[{index}] name 길이 {0}")
    @ValueSource(strings = {"", " ", "     "})
    @DisplayName("R1.2 name 이 비었거나 공백뿐이면 400")
    void r1_2_name_blank_returns400(String name) {
        assertProblem(post("/api/products", body(name, 1000, 1)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 name 이 없으면 400")
    void r1_2_name_missing_returns400() {
        assertProblem(post("/api/products", body(null, 1000, 1)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 name 이 탭/개행뿐이어도 공백만으로 보아 400")
    void r1_2_name_whitespaceControlChars_returns400() {
        assertProblem(post("/api/products", body("\t\n ", 1000, 1)), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "[{index}] price={0} -> {1}")
    @CsvSource({"1,201", "10000000,201", "0,400", "-1,400", "10000001,400", "99999999999,400"})
    @DisplayName("R1.2 price 는 1 ~ 10,000,000 만 허용한다")
    void r1_2_price_boundaries(long price, int expectedStatus) {
        ResponseEntity<String> r = post("/api/products", body("p", price, 1));

        assertThat(statusOf(r)).isEqualTo(expectedStatus);
    }

    @ParameterizedTest(name = "[{index}] stock={0} -> {1}")
    @CsvSource({"0,201", "1000000,201", "-1,400", "1000001,400", "99999999999,400"})
    @DisplayName("R1.2 stock 은 0 ~ 1,000,000 만 허용한다")
    void r1_2_stock_boundaries(long stock, int expectedStatus) {
        ResponseEntity<String> r = post("/api/products", body("p", 1000, stock));

        assertThat(statusOf(r)).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("R1.2 price 가 없으면 400")
    void r1_2_price_missing_returns400() {
        assertProblem(post("/api/products", body("p", null, 1)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 stock 이 없으면 400")
    void r1_2_stock_missing_returns400() {
        assertProblem(post("/api/products", body("p", 1000, null)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 price 가 숫자가 아니면 400")
    void r1_2_price_notNumber_returns400() {
        assertProblem(post("/api/products", "{\"name\":\"p\",\"price\":\"abc\",\"stock\":1}"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 price 가 소수이면 400 (절사하지 않는다)")
    void r1_2_price_decimal_returns400() {
        assertProblem(post("/api/products", "{\"name\":\"p\",\"price\":1000.5,\"stock\":1}"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 검증에 실패한 상품은 생성되지 않는다")
    void r1_2_invalidRequest_doesNotCreateProduct() {
        String name = uid("invalid");

        post("/api/products", body(name, 0, 1));

        Integer count = jdbc.queryForObject("select count(*) from products where name = ?", Integer.class, name);
        assertThat(count).isZero();
    }

    // ---------------------------------------------------------------- R1.3

    @Test
    @DisplayName("R1.3 조회하면 {id,name,price,stock,reserved,available} 를 200 으로 돌려준다")
    void r1_3_get_returnsAllFields() {
        long id = createProduct("키보드", 55_000, 9);

        ResponseEntity<String> r = getProduct(id);

        assertThat(statusOf(r)).isEqualTo(200);
        JsonNode p = json(r);
        assertThat(p.get("id").asLong()).isEqualTo(id);
        assertThat(p.get("name").asText()).isEqualTo("키보드");
        assertThat(p.get("price").asLong()).isEqualTo(55_000L);
        assertThat(p.get("stock").asInt()).isEqualTo(9);
        assertThat(p.get("reserved").asInt()).isZero();
        assertThat(p.get("available").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("R1.3 없는 상품은 404 PRODUCT_NOT_FOUND")
    void r1_3_get_unknownId_returns404() {
        assertProblem(getProduct(987_654_321L), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.3 id 가 0 이나 음수여도 404 (존재하지 않는 상품)")
    void r1_3_get_nonPositiveId_returns404() {
        assertProblem(getProduct(0), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.3 id 가 숫자가 아니면 400")
    void r1_3_get_nonNumericId_returns400() {
        assertProblem(get("/api/products/abc"), 400, "VALIDATION_ERROR");
    }

    // ---------------------------------------------------------------- R1.4

    @Test
    @DisplayName("R1.4 주문으로 예약되면 reserved 가 늘고 available = stock - reserved 이다 (stock 은 그대로)")
    void r1_4_available_isStockMinusReserved_afterReservation() {
        long productId = createProduct("모니터", 200_000, 10);
        orderOk(uid("u"), orderBody(null, item(productId, 3)));

        JsonNode p = json(getProduct(productId));

        assertThat(p.get("stock").asInt()).isEqualTo(10);
        assertThat(p.get("reserved").asInt()).isEqualTo(3);
        assertThat(p.get("available").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("R1.4 결제 승인 후에는 stock 이 줄고 reserved 는 0 이 되어 available 이 유지된다")
    void r1_4_available_isStockMinusReserved_afterPayment() {
        long productId = createProduct("스피커", 80_000, 10);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 4)));
        payOk(orderId);

        JsonNode p = json(getProduct(productId));

        assertThat(p.get("stock").asInt()).isEqualTo(6);
        assertThat(p.get("reserved").asInt()).isZero();
        assertThat(p.get("available").asInt()).isEqualTo(6);
    }
}
