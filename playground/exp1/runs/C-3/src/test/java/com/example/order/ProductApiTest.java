package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** R1(상품 등록), R2(상품 조회). */
@SuppressWarnings("rawtypes")
class ProductApiTest extends AbstractIntegrationTest {

    private ResponseEntity<Map> createWith(Map<String, Object> body) {
        return post("/api/products", body);
    }

    // ================= R1 =================

    @Test
    @DisplayName("R1: 유효한 요청이면 201 + Location(/api/products/{id}) + {id,name,price,stock} 본문")
    void r1_create_valid_returns201_withLocationAndBody() {
        ResponseEntity<Map> res = createWith(Map.of("name", "키보드", "price", 30000, "stock", 10));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = num(res.getBody(), "id");
        assertThat(res.getHeaders().getLocation()).isNotNull();
        assertThat(res.getHeaders().getLocation().toString()).endsWith("/api/products/" + id);
        assertThat(res.getBody()).containsOnlyKeys("id", "name", "price", "stock");
        assertThat(res.getBody().get("name")).isEqualTo("키보드");
        assertThat(num(res.getBody(), "price")).isEqualTo(30000L);
        assertThat(num(res.getBody(), "stock")).isEqualTo(10L);
    }

    @Test
    @DisplayName("R1: stock 0은 허용되어 201")
    void r1_create_stockZero_allowed() {
        ResponseEntity<Map> res = createWith(Map.of("name", "품절상품", "price", 100, "stock", 0));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(num(res.getBody(), "stock")).isZero();
    }

    @Test
    @DisplayName("R1: name 누락이면 400")
    void r1_create_nameMissing_returns400() {
        ResponseEntity<Map> res = createWith(Map.of("price", 100, "stock", 1));

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest(name = "name=''{0}''")
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("R1: name이 빈 문자열/공백만이면 400")
    void r1_create_nameBlank_returns400(String name) {
        ResponseEntity<Map> res = createWith(Map.of("name", name, "price", 100, "stock", 1));

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest(name = "price={0}")
    @ValueSource(longs = {0, -1, -1000})
    @DisplayName("R1: price가 0 또는 음수이면 400")
    void r1_create_priceNotPositive_returns400(long price) {
        ResponseEntity<Map> res = createWith(Map.of("name", "상품", "price", price, "stock", 1));

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R1: price 누락이면 400")
    void r1_create_priceMissing_returns400() {
        ResponseEntity<Map> res = createWith(Map.of("name", "상품", "stock", 1));

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest(name = "stock={0}")
    @ValueSource(ints = {-1, -100})
    @DisplayName("R1: stock이 음수이면 400")
    void r1_create_stockNegative_returns400(int stock) {
        ResponseEntity<Map> res = createWith(Map.of("name", "상품", "price", 100, "stock", stock));

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R1: stock 누락이면 400 (0으로 채워지지 않음)")
    void r1_create_stockMissing_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "상품");
        body.put("price", 100);

        assertProblem(createWith(body), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R1: 검증 실패한 요청은 상품을 저장하지 않는다")
    void r1_create_invalid_doesNotPersist() {
        createWith(Map.of("name", " ", "price", 100, "stock", 1));

        Long count = jdbc.queryForObject("SELECT count(*) FROM products", Long.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("R1: 정수 필드에 소수가 오면 400")
    void r1_create_fractionalStock_returns400() {
        ResponseEntity<Map> res = postRaw("/api/products", "{\"name\":\"a\",\"price\":100,\"stock\":1.5}");

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    // ================= R2 =================

    @Test
    @DisplayName("R2: 존재하는 상품 조회 시 200 + {id,name,price,stock}")
    void r2_get_existing_returns200WithShape() {
        long id = createProduct("마우스", 15000, 7);

        ResponseEntity<Map> res = get("/api/products/" + id);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsOnlyKeys("id", "name", "price", "stock");
        assertThat(num(res.getBody(), "id")).isEqualTo(id);
        assertThat(res.getBody().get("name")).isEqualTo("마우스");
        assertThat(num(res.getBody(), "price")).isEqualTo(15000L);
        assertThat(num(res.getBody(), "stock")).isEqualTo(7L);
    }

    @Test
    @DisplayName("R2: 없는 상품 id는 404")
    void r2_get_unknownId_returns404() {
        ResponseEntity<Map> res = get("/api/products/999999");

        assertProblem(res, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("R2: 숫자가 아닌 id는 400")
    void r2_get_nonNumericId_returns400() {
        ResponseEntity<Map> res = get("/api/products/abc");

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }
}
