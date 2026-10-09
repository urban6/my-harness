package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.order.support.AbstractIntegrationTest;

/** R1 상품 등록, R2 상품 조회. */
class ProductApiTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------ R1

    @Test
    @DisplayName("R1: 유효한 요청이면 201 + Location(/api/products/{id}) + {id,name,price,stock} 본문")
    void r1_createProduct_returns201WithLocationAndBody() {
        ResponseEntity<Map> res = rest.postForEntity("/api/products",
                Map.of("name", "키보드", "price", 35000, "stock", 10), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(res);
        assertThat(res.getHeaders().getLocation().toString()).endsWith("/api/products/" + id);
        assertThat(res.getBody()).containsOnlyKeys("id", "name", "price", "stock")
                .containsEntry("name", "키보드")
                .containsEntry("price", 35000)
                .containsEntry("stock", 10);
    }

    @Test
    @DisplayName("R1: stock 0은 허용된다")
    void r1_createProduct_allowsZeroStock() {
        ResponseEntity<Map> res = rest.postForEntity("/api/products",
                Map.of("name", "품절상품", "price", 1000, "stock", 0), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsEntry("stock", 0);
    }

    @Test
    @DisplayName("R1: Location이 가리키는 상품을 조회하면 등록 응답과 같은 본문이다")
    void r1_createProduct_locationPointsToSameBody() {
        ResponseEntity<Map> created = rest.postForEntity("/api/products",
                Map.of("name", "모니터", "price", 250000, "stock", 3), Map.class);

        ResponseEntity<Map> fetched = rest.getForEntity(created.getHeaders().getLocation(), Map.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(created.getBody());
    }

    static Stream<Arguments> invalidProductBodies() {
        return Stream.of(
                Arguments.of("name null", "{\"name\":null,\"price\":1000,\"stock\":1}"),
                Arguments.of("name 누락", "{\"price\":1000,\"stock\":1}"),
                Arguments.of("name 빈 문자열", "{\"name\":\"\",\"price\":1000,\"stock\":1}"),
                Arguments.of("name 공백만", "{\"name\":\"   \",\"price\":1000,\"stock\":1}"),
                Arguments.of("price 0", "{\"name\":\"a\",\"price\":0,\"stock\":1}"),
                Arguments.of("price 음수", "{\"name\":\"a\",\"price\":-1,\"stock\":1}"),
                Arguments.of("price 누락", "{\"name\":\"a\",\"stock\":1}"),
                Arguments.of("stock -1", "{\"name\":\"a\",\"price\":1000,\"stock\":-1}"),
                Arguments.of("stock 누락", "{\"name\":\"a\",\"price\":1000}"));
    }

    @ParameterizedTest(name = "R1: {0} -> 400")
    @MethodSource("invalidProductBodies")
    @DisplayName("R1: 검증 위반 요청은 400 problem+json")
    void r1_createProduct_invalidBody_returns400(String caseName, String json) {
        ResponseEntity<Map> res = postRaw("/api/products", json);

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------ R2

    @Test
    @DisplayName("R2: 존재하는 상품은 200 + {id,name,price,stock}")
    void r2_getProduct_returns200WithBody() {
        long id = createProduct("마우스", 20000, 7);

        ResponseEntity<Map> res = getProduct(id);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsOnlyKeys("id", "name", "price", "stock")
                .containsEntry("id", (int) id)
                .containsEntry("name", "마우스")
                .containsEntry("price", 20000)
                .containsEntry("stock", 7);
    }

    @Test
    @DisplayName("R2: 없는 id는 404")
    void r2_getProduct_unknownId_returns404() {
        ResponseEntity<Map> res = getProductRaw("999999");

        assertProblem(res, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("R2: 숫자가 아닌 id(/api/products/abc)는 400")
    void r2_getProduct_nonNumericId_returns400() {
        ResponseEntity<Map> res = getProductRaw("abc");

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map> getProductRaw(String id) {
        return rest.getForEntity("/api/products/" + id, Map.class);
    }
}
