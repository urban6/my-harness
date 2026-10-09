package com.example.order;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class ProductApiTest extends IntegrationTest {

    @Test
    @DisplayName("R1 상품 등록: 201 + Location, 본문은 R2 응답과 같은 형태")
    void create_returns201WithLocationAndBody() throws Exception {
        String location = postJson("/api/products", """
                {"name":"키보드","price":30000,"stock":5}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("키보드"))
                .andExpect(jsonPath("$.price").value(30000))
                .andExpect(jsonPath("$.stock").value(5))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("키보드"));
    }

    @Test
    @DisplayName("R1 상품 등록: stock 0 은 허용")
    void create_allowsZeroStock() throws Exception {
        postJson("/api/products", """
                {"name":"품절상품","price":1,"stock":0}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/products/1")))
                .andExpect(jsonPath("$.stock").value(0));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"price\":1000,\"stock\":1}",                    // name 누락
            "{\"name\":\"\",\"price\":1000,\"stock\":1}",       // 빈 name
            "{\"name\":\"   \",\"price\":1000,\"stock\":1}",    // 공백만
            "{\"name\":\"a\",\"stock\":1}",                     // price 누락
            "{\"name\":\"a\",\"price\":0,\"stock\":1}",         // price = 0
            "{\"name\":\"a\",\"price\":-1,\"stock\":1}",        // price < 0
            "{\"name\":\"a\",\"price\":1000}",                  // stock 누락
            "{\"name\":\"a\",\"price\":1000,\"stock\":-1}"      // stock < 0
    })
    @DisplayName("R1 상품 등록: 규칙 위반은 400")
    void create_returns400_whenInvalid(String body) throws Exception {
        postJson("/api/products", body).andExpect(problem(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("R2 상품 조회: 200 {id, name, price, stock}")
    void get_returnsProduct() throws Exception {
        long id = createProduct("마우스", 15000, 7);

        mvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("마우스"))
                .andExpect(jsonPath("$.price").value(15000))
                .andExpect(jsonPath("$.stock").value(7));
    }

    @Test
    @DisplayName("R2 상품 조회: 없으면 404")
    void get_returns404_whenMissing() throws Exception {
        mvc.perform(get("/api/products/{id}", 999))
                .andExpect(problem(HttpStatus.NOT_FOUND));
    }
}
