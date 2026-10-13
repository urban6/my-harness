package com.example.order.product;

import static com.example.order.support.ApiTestSupport.assertProblem;
import static com.example.order.support.ApiTestSupport.productJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.order.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;

/** R8 에러 포맷: R1~R2의 모든 에러 + JSON 파싱 실패가 RFC 9457 problem+json (01_api_design.md §3.2, §4). */
@DisplayName("R8 에러 포맷")
class ProblemDetailsApiTest extends AbstractIntegrationTest {

    private static final String MALFORMED = "malformed-request-body";
    private static final String MALFORMED_TITLE = "Malformed Request Body";

    private MockHttpServletRequestBuilder postJson(String body) {
        return post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---------- JSON 파싱 실패 -> 400 malformed-request-body ----------

    static Stream<Arguments> malformedBodies() {
        return Stream.of(
                Arguments.of("깨진 JSON", "{\"name\":\"A\",\"price\":1000,"),
                Arguments.of("최상위가 배열", "[]"),
                Arguments.of("최상위가 문자열", "\"text\""),
                Arguments.of("price 문자열 abc", "{\"name\":\"A\",\"price\":\"abc\",\"stock\":1}"),
                Arguments.of("price 숫자 문자열 \"1000\"", "{\"name\":\"A\",\"price\":\"1000\",\"stock\":1}"),
                Arguments.of("price 소수 1000.5", "{\"name\":\"A\",\"price\":1000.5,\"stock\":1}"),
                Arguments.of("price 불리언 true", "{\"name\":\"A\",\"price\":true,\"stock\":1}"),
                Arguments.of("price Long 범위 초과", "{\"name\":\"A\",\"price\":9223372036854775808,\"stock\":1}"),
                Arguments.of("stock 문자열 x", "{\"name\":\"A\",\"price\":1000,\"stock\":\"x\"}"),
                Arguments.of("stock 숫자 문자열 \"5\"", "{\"name\":\"A\",\"price\":1000,\"stock\":\"5\"}"),
                Arguments.of("stock 소수 1.5", "{\"name\":\"A\",\"price\":1000,\"stock\":1.5}"),
                Arguments.of("stock 불리언 true", "{\"name\":\"A\",\"price\":1000,\"stock\":true}"),
                Arguments.of("stock Integer 범위 초과", "{\"name\":\"A\",\"price\":1000,\"stock\":2147483648}"));
    }

    @ParameterizedTest(name = "R8 {0} -> 400 malformed-request-body problem+json")
    @MethodSource("malformedBodies")
    void r8_malformedBody_returns400ProblemJson(String caseName, String body) throws Exception {
        MvcResult result = mvc.perform(postJson(body)).andReturn();

        JsonNode problem = assertProblem(result, 400, MALFORMED, MALFORMED_TITLE);
        assertThat(problem.has("errors")).isFalse();
    }

    @Test
    @DisplayName("R8 본문 없음(Content-Type: application/json)이면 400 malformed-request-body")
    void r8_emptyBody_returns400ProblemJson() throws Exception {
        MvcResult result = mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)).andReturn();

        JsonNode problem = assertProblem(result, 400, MALFORMED, MALFORMED_TITLE);
        assertThat(problem.get("detail").asText()).isEqualTo("요청 본문을 읽을 수 없습니다. 올바른 JSON 형식인지 확인하세요.");
    }

    @Test
    @DisplayName("R8 깨진 JSON의 detail은 일반 문구이고 파서 내부 메시지를 노출하지 않는다")
    void r8_brokenJson_detailIsGeneric() throws Exception {
        MvcResult result = mvc.perform(postJson("{\"name\":\"A\",\"price\":1000,")).andReturn();

        JsonNode problem = assertProblem(result, 400, MALFORMED, MALFORMED_TITLE);
        assertThat(problem.get("detail").asText()).isEqualTo("요청 본문을 읽을 수 없습니다. 올바른 JSON 형식인지 확인하세요.");
    }

    @Test
    @DisplayName("R8 price에 \"abc\"를 넣으면 detail에 price 필드명이 포함된다")
    void r8_priceAbc_detailMentionsPrice() throws Exception {
        MvcResult result = mvc.perform(postJson("{\"name\":\"A\",\"price\":\"abc\",\"stock\":1}")).andReturn();

        JsonNode problem = assertProblem(result, 400, MALFORMED, MALFORMED_TITLE);
        assertThat(problem.get("detail").asText()).contains("price");
        assertThat(problem.get("detail").asText()).isEqualTo("필드 'price'의 값 형식이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("R8 stock에 \"x\"를 넣으면 detail에 stock 필드명이 포함된다")
    void r8_stockX_detailMentionsStock() throws Exception {
        MvcResult result = mvc.perform(postJson("{\"name\":\"A\",\"price\":1000,\"stock\":\"x\"}")).andReturn();

        JsonNode problem = assertProblem(result, 400, MALFORMED, MALFORMED_TITLE);
        assertThat(problem.get("detail").asText()).contains("stock");
    }

    // ---------- Accept 협상과 무관하게 problem+json ----------

    record ErrorScenario(String name, Supplier<MockHttpServletRequestBuilder> request, int status, String type, String title) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<ErrorScenario> errorScenarios() {
        return Stream.of(
                new ErrorScenario("400 validation-failed",
                        () -> post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{}"),
                        400, "validation-failed", "Validation Failed"),
                new ErrorScenario("400 malformed-request-body",
                        () -> post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"),
                        400, MALFORMED, MALFORMED_TITLE),
                new ErrorScenario("400 invalid-path-parameter", () -> get("/api/products/abc"),
                        400, "invalid-path-parameter", "Invalid Path Parameter"),
                new ErrorScenario("404 product-not-found", () -> get("/api/products/777777777"),
                        404, "product-not-found", "Product Not Found"));
    }

    static Stream<Arguments> errorScenariosWithAccept() {
        return errorScenarios().flatMap(s -> Stream.of(
                Arguments.of(s, null),
                Arguments.of(s, "*/*"),
                Arguments.of(s, MediaType.APPLICATION_JSON_VALUE),
                Arguments.of(s, MediaType.APPLICATION_PROBLEM_JSON_VALUE)));
    }

    @ParameterizedTest(name = "R8 [{0}] Accept={1} -> problem+json")
    @MethodSource("errorScenariosWithAccept")
    void r8_errorResponses_areProblemJson_regardlessOfAccept(ErrorScenario scenario, String accept)
            throws Exception {
        MockHttpServletRequestBuilder request = scenario.request().get();
        if (accept != null) {
            request.header("Accept", accept);
        }

        MvcResult result = mvc.perform(request).andReturn();

        assertProblem(result, scenario.status(), scenario.type(), scenario.title());
    }

    @Test
    @DisplayName("R8 validation-failed 응답은 errors 배열 원소마다 field와 message를 가진다")
    void r8_validationFailed_errorsHaveFieldAndMessage() throws Exception {
        MvcResult result = mvc.perform(postJson(productJson("A", 0, -1))).andReturn();

        JsonNode problem = assertProblem(result, 400, "validation-failed", "Validation Failed");
        assertThat(problem.get("errors").isArray()).isTrue();
        assertThat(problem.get("errors")).hasSize(2);
        problem.get("errors").forEach(e -> {
            assertThat(e.hasNonNull("field")).isTrue();
            assertThat(e.hasNonNull("message")).isTrue();
        });
    }

    @Test
    @DisplayName("R8 404 응답의 type은 product-not-found이고 errors 필드가 없다")
    void r8_notFound_hasNoErrorsField() throws Exception {
        MvcResult result = mvc.perform(get("/api/products/{id}", 31337L)).andReturn();

        JsonNode problem = assertProblem(result, 404, "product-not-found", "Product Not Found");
        assertThat(problem.has("errors")).isFalse();
    }

    @Test
    @DisplayName("R8 같은 type의 title은 상황이 달라도 불변이다")
    void r8_sameType_hasSameTitle() throws Exception {
        JsonNode a = assertProblem(mvc.perform(postJson("{")).andReturn(), 400, MALFORMED, MALFORMED_TITLE);
        JsonNode b = assertProblem(
                mvc.perform(postJson("{\"name\":\"A\",\"price\":\"abc\",\"stock\":1}")).andReturn(),
                400, MALFORMED, MALFORMED_TITLE);

        assertThat(a.get("title").asText()).isEqualTo(b.get("title").asText());
        assertThat(a.get("detail").asText()).isNotEqualTo(b.get("detail").asText());
    }
}
