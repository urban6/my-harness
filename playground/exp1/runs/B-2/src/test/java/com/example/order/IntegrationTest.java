package com.example.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 실제 PostgreSQL(Testcontainers) 위에서 전체 컨텍스트를 띄우는 통합 테스트 기반. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE order_items, orders, products RESTART IDENTITY CASCADE");
    }

    /** R8: RFC 9457 Problem Details 형태의 에러 응답인지 검증한다. */
    protected static ResultMatcher problem(HttpStatus expected) {
        List<ResultMatcher> matchers = List.of(
                status().is(expected.value()),
                content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                jsonPath("$.type").isString(),
                jsonPath("$.title").isString(),
                jsonPath("$.status").value(expected.value()),
                jsonPath("$.detail").isString());
        return result -> {
            for (ResultMatcher matcher : matchers) {
                matcher.match(result);
            }
        };
    }

    protected ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected long createProduct(String name, long price, int stock) throws Exception {
        String body = """
                {"name":"%s","price":%d,"stock":%d}
                """.formatted(name, price, stock);
        return readId(postJson("/api/products", body).andExpect(status().isCreated()));
    }

    protected long createOrder(long productId, int quantity) throws Exception {
        return readId(postJson("/api/orders", orderBody(productId, quantity)).andExpect(status().isCreated()));
    }

    protected static String orderBody(long productId, int quantity) {
        return """
                {"items":[{"productId":%d,"quantity":%d}]}
                """.formatted(productId, quantity);
    }

    protected int stockOf(long productId) throws Exception {
        String json = mvc.perform(get("/api/products/{id}", productId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("stock").asInt();
    }

    protected long readId(ResultActions actions) throws Exception {
        JsonNode node = objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
        return node.get("id").asLong();
    }
}
