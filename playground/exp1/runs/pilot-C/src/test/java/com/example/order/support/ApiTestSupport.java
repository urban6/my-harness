package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 상품 API 테스트 공용 헬퍼 (요청 생성, problem+json 공통 단정). */
public final class ApiTestSupport {

    public static final String PROBLEM_BASE = "https://example.com/problems/";
    public static final ObjectMapper JSON = new ObjectMapper();

    private ApiTestSupport() {
    }

    public static String productJson(String name, Object price, Object stock) {
        return "{\"name\":" + quote(name) + ",\"price\":" + price + ",\"stock\":" + stock + "}";
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static MvcResult createProduct(MockMvc mvc, String body) throws Exception {
        return mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    public static JsonNode read(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** problem+json 공통 계약(R8): Content-Type, 필수 필드 4개, status 일치, 내부 정보 비노출. */
    public static JsonNode assertProblem(MvcResult result, int status, String typeSuffix, String title)
            throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        MediaType contentType = MediaType.parseMediaType(result.getResponse().getContentType());
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("problem+json");

        JsonNode body = read(result);
        for (String f : List.of("type", "title", "status", "detail")) {
            assertThat(body.hasNonNull(f)).as("필수 필드 %s 존재 및 non-null", f).isTrue();
        }
        assertThat(body.get("type").asText()).isEqualTo(PROBLEM_BASE + typeSuffix);
        assertThat(body.get("title").asText()).isEqualTo(title);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("detail").asText()).isNotBlank()
                .doesNotContain("java.", "Exception", "com.fasterxml", "org.hibernate", "org.springframework");
        return body;
    }

    public static Set<String> errorFields(JsonNode problem) {
        Set<String> fields = new TreeSet<>();
        problem.get("errors").forEach(e -> fields.add(e.get("field").asText()));
        return fields;
    }

    public static List<String> errorFieldList(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        problem.get("errors").forEach(e -> fields.add(e.get("field").asText()));
        return fields;
    }
}
