package com.example.order.product;

import static com.example.order.support.ApiTestSupport.assertProblem;
import static com.example.order.support.ApiTestSupport.createProduct;
import static com.example.order.support.ApiTestSupport.productJson;
import static com.example.order.support.ApiTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.order.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** R2 상품 조회: GET /api/products/{id} (01_api_design.md §2.2). */
@DisplayName("R2 상품 조회")
class ProductGetApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R2 등록 후 조회하면 200이고 4개 필드가 등록 값과 일치한다")
    void r2_get_returns200_withMatchingFields() throws Exception {
        long id = read(createProduct(mvc, productJson("무선 키보드", 39000, 10))).get("id").asLong();

        MvcResult result = mvc.perform(get("/api/products/{id}", id)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        JsonNode body = read(result);
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name", "price", "stock");
        assertThat(body.get("id").asLong()).isEqualTo(id);
        assertThat(body.get("name").asText()).isEqualTo("무선 키보드");
        assertThat(body.get("price").asLong()).isEqualTo(39000L);
        assertThat(body.get("stock").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R2 경계값(price=1, stock=0, 3,000,000,000원)과 유니코드 name이 조회 시 보존된다")
    void r2_get_preservesBoundaryValues() throws Exception {
        long id1 = read(createProduct(mvc, productJson("A", 1, 0))).get("id").asLong();
        long id2 = read(createProduct(mvc, productJson("한글 😀 name", 3_000_000_000L, 2_147_483_647))).get("id")
                .asLong();

        JsonNode b1 = read(mvc.perform(get("/api/products/{id}", id1)).andReturn());
        JsonNode b2 = read(mvc.perform(get("/api/products/{id}", id2)).andReturn());

        assertThat(b1.get("price").asLong()).isEqualTo(1L);
        assertThat(b1.get("stock").asInt()).isZero();
        assertThat(b2.get("name").asText()).isEqualTo("한글 😀 name");
        assertThat(b2.get("price").asLong()).isEqualTo(3_000_000_000L);
        assertThat(b2.get("stock").asInt()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("R2 존재하지 않는 id는 404 product-not-found")
    void r2_get_returns404_whenNotFound() throws Exception {
        MvcResult result = mvc.perform(get("/api/products/{id}", 424242424L)).andReturn();

        JsonNode problem = assertProblem(result, 404, "product-not-found", "Product Not Found");
        assertThat(problem.get("detail").asText()).isEqualTo("상품을 찾을 수 없습니다: id=424242424");
    }

    @ParameterizedTest(name = "R2 id={0} (숫자이나 미존재) -> 404")
    @ValueSource(strings = {"0", "-1"})
    void r2_get_returns404_forZeroAndNegativeId(String id) throws Exception {
        MvcResult result = mvc.perform(get("/api/products/" + id)).andReturn();

        assertProblem(result, 404, "product-not-found", "Product Not Found");
    }

    @ParameterizedTest(name = "R2 id=''{0}'' (형식 오류) -> 400 invalid-path-parameter")
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999", "12abc"})
    void r2_get_returns400_forInvalidIdFormat(String id) throws Exception {
        MvcResult result = mvc.perform(get("/api/products/" + id)).andReturn();

        JsonNode problem = assertProblem(result, 400, "invalid-path-parameter", "Invalid Path Parameter");
        assertThat(problem.get("detail").asText()).isEqualTo("경로 변수 'id'는 정수여야 합니다.");
        assertThat(problem.get("detail").asText()).doesNotContain(id);
    }
}
