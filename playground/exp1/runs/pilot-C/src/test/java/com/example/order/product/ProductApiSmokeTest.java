package com.example.order.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.order.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ProductApiSmokeTest extends AbstractIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void create_returns201WithLocation() throws Exception {
        MvcResult result = mvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"무선 키보드","price":39000,"stock":10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("무선 키보드"))
                .andExpect(jsonPath("$.price").value(39000))
                .andExpect(jsonPath("$.stock").value(10))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        long id = body.get("id").asLong();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/products/" + id);
    }

    @Test
    void get_returns200_afterCreate() throws Exception {
        MvcResult created = mvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"마우스","price":15000,"stock":3}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("마우스"))
                .andExpect(jsonPath("$.price").value(15000))
                .andExpect(jsonPath("$.stock").value(3));
    }

    @Test
    void get_returns404ProblemJson_whenNotFound() throws Exception {
        mvc.perform(get("/api/products/{id}", 999999L).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://example.com/problems/product-not-found"))
                .andExpect(jsonPath("$.title").value("Product Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("상품을 찾을 수 없습니다: id=999999"));
    }
}
