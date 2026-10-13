package com.example.order.product;

import static com.example.order.support.ApiTestSupport.assertProblem;
import static com.example.order.support.ApiTestSupport.createProduct;
import static com.example.order.support.ApiTestSupport.errorFieldList;
import static com.example.order.support.ApiTestSupport.errorFields;
import static com.example.order.support.ApiTestSupport.productJson;
import static com.example.order.support.ApiTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.order.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** R1 상품 등록: POST /api/products (01_api_design.md §2.1, §3.2). */
@DisplayName("R1 상품 등록")
class ProductCreateApiTest extends AbstractIntegrationTest {

    // ---------- 정상 / 경계값 ----------

    @Test
    @DisplayName("R1 정상 등록하면 201, Location은 /api/products/{본문 id}, 본문은 {id,name,price,stock}")
    void r1_create_returns201_withLocationAndBody() throws Exception {
        MvcResult result = createProduct(mvc, productJson("무선 키보드", 39000, 10));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = read(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/products/" + body.get("id").asLong());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name", "price", "stock");
        assertThat(body.get("name").asText()).isEqualTo("무선 키보드");
        assertThat(body.get("price").asLong()).isEqualTo(39000L);
        assertThat(body.get("stock").asInt()).isEqualTo(10);
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
    }

    @Test
    @DisplayName("R1 등록 응답 본문은 Location으로 조회한 GET 응답과 형태·값이 같다")
    void r1_create_bodyEqualsGetResponse() throws Exception {
        MvcResult created = createProduct(mvc, productJson("모니터", 250000, 4));

        MvcResult fetched = mvc.perform(get(created.getResponse().getHeader("Location"))).andReturn();

        assertThat(fetched.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(fetched)).isEqualTo(read(created));
    }

    @Test
    @DisplayName("R1 경계값 price=1, stock=0 이면 201")
    void r1_create_minBoundary_price1_stock0() throws Exception {
        MvcResult result = createProduct(mvc, "{\"name\":\"A\",\"price\":1,\"stock\":0}");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(result).get("price").asLong()).isEqualTo(1L);
        assertThat(read(result).get("stock").asInt()).isZero();
    }

    @Test
    @DisplayName("R1 name 255자는 201이고 그대로 저장된다")
    void r1_create_name255_returns201() throws Exception {
        String name = "가".repeat(255);

        MvcResult result = createProduct(mvc, productJson(name, 1000, 1));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(result).get("name").asText()).isEqualTo(name);
    }

    @Test
    @DisplayName("R1 price가 int 범위를 넘는 Long 값(3,000,000,000)도 201")
    void r1_create_priceAboveIntMax_returns201() throws Exception {
        MvcResult result = createProduct(mvc, productJson("고가 장비", 3_000_000_000L, 1));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(result).get("price").asLong()).isEqualTo(3_000_000_000L);
    }

    @Test
    @DisplayName("R1 name은 trim하지 않고 앞뒤 공백 그대로 저장·응답한다")
    void r1_create_nameNotTrimmed() throws Exception {
        MvcResult created = createProduct(mvc, productJson("  padded  ", 1000, 1));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(created).get("name").asText()).isEqualTo("  padded  ");
        MvcResult fetched = mvc.perform(get(created.getResponse().getHeader("Location"))).andReturn();
        assertThat(read(fetched).get("name").asText()).isEqualTo("  padded  ");
    }

    @Test
    @DisplayName("R1 요청 본문의 id는 무시되고 서버가 id를 생성한다")
    void r1_create_ignoresClientId() throws Exception {
        MvcResult result = createProduct(mvc, "{\"id\":987654321,\"name\":\"A\",\"price\":1000,\"stock\":1}");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(result).get("id").asLong()).isNotEqualTo(987654321L);
    }

    @Test
    @DisplayName("R1 같은 name을 두 번 등록해도 모두 201이고 id는 서로 다르다")
    void r1_create_duplicateNameAllowed() throws Exception {
        MvcResult first = createProduct(mvc, productJson("중복 상품", 1000, 1));
        MvcResult second = createProduct(mvc, productJson("중복 상품", 1000, 1));

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(first).get("id").asLong()).isNotEqualTo(read(second).get("id").asLong());
    }

    // ---------- 규칙 위반 -> 400 validation-failed ----------

    static Stream<Arguments> validationViolations() {
        return Stream.of(
                Arguments.of("name 누락", "{\"price\":1000,\"stock\":1}", Set.of("name")),
                Arguments.of("name null", "{\"name\":null,\"price\":1000,\"stock\":1}", Set.of("name")),
                Arguments.of("name 빈 문자열", productJson("", 1000, 1), Set.of("name")),
                Arguments.of("name 공백만", productJson("   ", 1000, 1), Set.of("name")),
                Arguments.of("name 탭/개행만", "{\"name\":\"\\t\\n\",\"price\":1000,\"stock\":1}", Set.of("name")),
                Arguments.of("name 256자", productJson("a".repeat(256), 1000, 1), Set.of("name")),
                Arguments.of("price 누락", "{\"name\":\"A\",\"stock\":1}", Set.of("price")),
                Arguments.of("price null", "{\"name\":\"A\",\"price\":null,\"stock\":1}", Set.of("price")),
                Arguments.of("price 0", productJson("A", 0, 1), Set.of("price")),
                Arguments.of("price -1", productJson("A", -1, 1), Set.of("price")),
                Arguments.of("stock 누락", "{\"name\":\"A\",\"price\":1000}", Set.of("stock")),
                Arguments.of("stock null", "{\"name\":\"A\",\"price\":1000,\"stock\":null}", Set.of("stock")),
                Arguments.of("stock -1", productJson("A", 1000, -1), Set.of("stock")),
                Arguments.of("빈 객체", "{}", Set.of("name", "price", "stock")),
                Arguments.of("세 필드 동시 위반", productJson(" ", 0, -1), Set.of("name", "price", "stock")));
    }

    @ParameterizedTest(name = "R1 {0} -> 400 validation-failed")
    @MethodSource("validationViolations")
    void r1_create_returns400ValidationFailed(String caseName, String body, Set<String> expectedFields)
            throws Exception {
        MvcResult result = createProduct(mvc, body);

        JsonNode problem = assertProblem(result, 400, "validation-failed", "Validation Failed");
        assertThat(errorFields(problem)).isEqualTo(expectedFields);
    }

    @Test
    @DisplayName("R1 검증 실패 시 detail은 고정 문구, errors는 field 오름차순이고 메시지는 설계 표와 같다")
    void r1_create_validationErrors_sortedWithFixedMessages() throws Exception {
        MvcResult result = createProduct(mvc, "{}");

        JsonNode problem = assertProblem(result, 400, "validation-failed", "Validation Failed");
        assertThat(problem.get("detail").asText()).isEqualTo("요청 본문 검증에 실패했습니다.");
        assertThat(errorFieldList(problem)).containsExactly("name", "price", "stock");
        assertThat(problem.get("errors").get(0).get("message").asText())
                .isEqualTo("name은 필수이며 공백만으로 구성될 수 없습니다.");
        assertThat(problem.get("errors").get(1).get("message").asText()).isEqualTo("price는 필수입니다.");
        assertThat(problem.get("errors").get(2).get("message").asText()).isEqualTo("stock은 필수입니다.");
    }
}
