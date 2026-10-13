package com.example.order.product;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.order.support.IntegrationTest;

class ProductApiTest extends IntegrationTest {

    @Nested
    @DisplayName("R1 상품 등록")
    class Create {

        @Test
        void create_returns201WithLocationAndBody() throws Exception {
            String location = postJson("/api/products", """
                    {"name":"키보드","price":50000,"stock":10}
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", endsWith("/api/products/1")))
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.name").value("키보드"))
                    .andExpect(jsonPath("$.price").value(50000))
                    .andExpect(jsonPath("$.stock").value(10))
                    .andReturn().getResponse().getHeader("Location");

            // 본문과 Location이 가리키는 R2 응답이 같은 형태·값이다.
            getJson(location.substring(location.indexOf("/api/")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.name").value("키보드"))
                    .andExpect(jsonPath("$.price").value(50000))
                    .andExpect(jsonPath("$.stock").value(10));
        }

        @Test
        void create_allowsZeroStock() throws Exception {
            postJson("/api/products", """
                    {"name":"품절상품","price":1,"stock":0}
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.stock").value(0));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{\"price\":1000,\"stock\":1}",                      // name 누락
                "{\"name\":\"\",\"price\":1000,\"stock\":1}",        // 빈 문자열
                "{\"name\":\"   \",\"price\":1000,\"stock\":1}",     // 공백만
                "{\"name\":\"a\",\"stock\":1}",                      // price 누락
                "{\"name\":\"a\",\"price\":0,\"stock\":1}",          // price = 0
                "{\"name\":\"a\",\"price\":-1,\"stock\":1}",         // price < 0
                "{\"name\":\"a\",\"price\":10.5,\"stock\":1}",       // 정수가 아닌 금액
                "{\"name\":\"a\",\"price\":1000}",                   // stock 누락
                "{\"name\":\"a\",\"price\":1000,\"stock\":-1}"       // stock < 0
        })
        void create_returns400_whenInvalid(String body) throws Exception {
            postJson("/api/products", body).andExpect(problem(400));
        }
    }

    @Nested
    @DisplayName("R2 상품 조회")
    class Get {

        @Test
        void get_returns200() throws Exception {
            long id = createProduct("마우스", 30000, 5);

            getJson("/api/products/" + id)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.name").value("마우스"))
                    .andExpect(jsonPath("$.price").value(30000))
                    .andExpect(jsonPath("$.stock").value(5));
        }

        @Test
        void get_returns404_whenMissing() throws Exception {
            getJson("/api/products/999").andExpect(problem(404));
        }
    }
}
