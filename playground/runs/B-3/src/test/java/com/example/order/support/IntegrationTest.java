package com.example.order.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 실제 PostgreSQL(Testcontainers) 위에서 HTTP 계약을 검증하는 통합 테스트 기반 클래스. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE order_items, orders, products RESTART IDENTITY CASCADE");
    }

    protected ResultActions postJson(String uri, String body) throws Exception {
        return mvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions getJson(String uri) throws Exception {
        return mvc.perform(get(uri));
    }

    protected long createProduct(String name, long price, int stock) throws Exception {
        String body = postJson("/api/products", """
                {"name":"%s","price":%d,"stock":%d}
                """.formatted(name, price, stock))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    protected JsonNode createOrder(String itemsJson) throws Exception {
        String body = postJson("/api/orders", "{\"items\":" + itemsJson + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    protected int stockOf(long productId) {
        return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    /** R8: RFC 9457 Problem Details 형식과 상태 코드를 함께 검증한다. */
    protected static ResultMatcher problem(int expectedStatus) {
        return result -> {
            status().is(expectedStatus).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.type").isString().match(result);
            jsonPath("$.title").isString().match(result);
            jsonPath("$.status").value(expectedStatus).match(result);
            jsonPath("$.detail").isString().match(result);
        };
    }
}
