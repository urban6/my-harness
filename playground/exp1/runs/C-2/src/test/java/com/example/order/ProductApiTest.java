package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

/** 상품 API 확장 테스트: R1(등록) · R2(조회). 기본 happy path 는 ProductApiSmokeTest. */
class ProductApiTest extends AbstractApiTest {

    @Nested
    @DisplayName("R1 상품 등록")
    class R1_CreateProduct {

        @Test
        @DisplayName("R1 유효한 요청이면 201 + Location(/api/products/{id}) + {id,name,price,stock} 본문")
        void validRequest_returns201WithLocationAndBody() {
            ResponseEntity<JsonNode> res = post("/api/products", Map.of("name", "키보드", "price", 35000, "stock", 10));

            assertThat(res.getStatusCode().value()).isEqualTo(201);
            JsonNode body = res.getBody();
            assertThat(fieldNames(body)).containsExactlyInAnyOrder("id", "name", "price", "stock");
            assertThat(locationOf(res).toString()).endsWith("/api/products/" + body.get("id").asLong());
            assertThat(body.get("name").asText()).isEqualTo("키보드");
            assertThat(body.get("price").asLong()).isEqualTo(35000);
            assertThat(body.get("stock").asInt()).isEqualTo(10);
        }

        @Test
        @DisplayName("R1 stock 0 은 허용되어 201")
        void stockZero_isAllowed() {
            ResponseEntity<JsonNode> res = post("/api/products", Map.of("name", "품절", "price", 1000, "stock", 0));

            assertThat(res.getStatusCode().value()).isEqualTo(201);
            assertThat(res.getBody().get("stock").asInt()).isZero();
            assertThat(stockOf(res.getBody().get("id").asLong())).isZero();
        }

        @Test
        @DisplayName("R1 등록한 상품이 DB 에 저장된다")
        void validRequest_persistsRow() {
            long id = createProduct("저장확인", 500, 3);

            assertThat(jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, id))
                    .isEqualTo("저장확인");
            assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", Long.class, id))
                    .isEqualTo(500L);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "{\"price\":100,\"stock\":1}",
            "{\"name\":null,\"price\":100,\"stock\":1}",
            "{\"name\":\"\",\"price\":100,\"stock\":1}",
            "{\"name\":\"   \",\"price\":100,\"stock\":1}"
        })
        @DisplayName("R1 name 이 누락/null/빈 문자열/공백뿐이면 400 validation-error(field=name)")
        void invalidName_returns400(String json) {
            ResponseEntity<JsonNode> res = postRaw("/api/products", json);

            assertProblem(res, 400, "validation-error");
            assertThat(res.getBody().get("errors").toString()).contains("\"field\":\"name\"");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isZero();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "{\"name\":\"a\",\"stock\":1}",
            "{\"name\":\"a\",\"price\":null,\"stock\":1}",
            "{\"name\":\"a\",\"price\":0,\"stock\":1}",
            "{\"name\":\"a\",\"price\":-1,\"stock\":1}"
        })
        @DisplayName("R1 price 가 누락/null/0/음수이면 400 validation-error(field=price)")
        void invalidPrice_returns400(String json) {
            ResponseEntity<JsonNode> res = postRaw("/api/products", json);

            assertProblem(res, 400, "validation-error");
            assertThat(res.getBody().get("errors").toString()).contains("\"field\":\"price\"");
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "{\"name\":\"a\",\"price\":100}",
            "{\"name\":\"a\",\"price\":100,\"stock\":null}",
            "{\"name\":\"a\",\"price\":100,\"stock\":-1}"
        })
        @DisplayName("R1 stock 이 누락/null/음수이면 400 validation-error(field=stock)")
        void invalidStock_returns400(String json) {
            ResponseEntity<JsonNode> res = postRaw("/api/products", json);

            assertProblem(res, 400, "validation-error");
            assertThat(res.getBody().get("errors").toString()).contains("\"field\":\"stock\"");
        }

        @Test
        @DisplayName("R1 빈 객체 {} 이면 name/price/stock 모두 위반으로 400")
        void emptyObject_reportsAllFields() {
            ResponseEntity<JsonNode> res = postRaw("/api/products", "{}");

            assertProblem(res, 400, "validation-error");
            String errors = res.getBody().get("errors").toString();
            assertThat(errors).contains("\"field\":\"name\"", "\"field\":\"price\"", "\"field\":\"stock\"");
        }
    }

    @Nested
    @DisplayName("R2 상품 조회")
    class R2_GetProduct {

        @Test
        @DisplayName("R2 존재하는 상품이면 200 + {id,name,price,stock}")
        void existing_returns200WithShape() {
            long id = createProduct("마우스", 12000, 5);

            ResponseEntity<JsonNode> res = get("/api/products/" + id);

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            JsonNode body = res.getBody();
            assertThat(fieldNames(body)).containsExactlyInAnyOrder("id", "name", "price", "stock");
            assertThat(body.get("id").asLong()).isEqualTo(id);
            assertThat(body.get("name").asText()).isEqualTo("마우스");
            assertThat(body.get("price").asLong()).isEqualTo(12000);
            assertThat(body.get("stock").asInt()).isEqualTo(5);
        }

        @Test
        @DisplayName("R2 없는 id 이면 404 product-not-found")
        void unknownId_returns404() {
            assertProblem(get("/api/products/424242"), 404, "product-not-found");
        }

        @Test
        @DisplayName("R2 path id 가 정수가 아니면(abc) 400 validation-error")
        void nonNumericId_returns400() {
            ResponseEntity<JsonNode> res = get("/api/products/abc");

            assertProblem(res, 400, "validation-error");
            assertThat(res.getBody().get("errors").toString()).contains("\"field\":\"id\"");
        }
    }
}
