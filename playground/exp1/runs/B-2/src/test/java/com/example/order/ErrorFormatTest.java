package com.example.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/** R8: 에러 응답은 RFC 9457 Problem Details(application/problem+json, type·title·status·detail). */
class ErrorFormatTest extends IntegrationTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"name\":\"a\",\"price\":1000,",                  // 잘린 JSON
            "not json",                                           // JSON 아님
            "{\"name\":\"a\",\"price\":\"abc\",\"stock\":1}",   // 타입 불일치
            "{\"name\":\"a\",\"price\":1.5,\"stock\":1}"        // 정수가 아닌 금액
    })
    @DisplayName("R8 상품 등록 본문 JSON 파싱 실패는 400 Problem Details")
    void productBodyParseFailure(String body) throws Exception {
        postJson("/api/products", body).andExpect(problem(HttpStatus.BAD_REQUEST));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"items\":[{\"productId\":1,\"quantity\":1}",      // 잘린 JSON
            "{\"items\":\"x\"}",                                  // 타입 불일치
            "{\"items\":[{\"productId\":1,\"quantity\":1.5}]}"    // 정수가 아닌 수량
    })
    @DisplayName("R8 주문 생성 본문 JSON 파싱 실패는 400 Problem Details")
    void orderBodyParseFailure(String body) throws Exception {
        postJson("/api/orders", body).andExpect(problem(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("R8 빈 본문은 400 Problem Details")
    void emptyBody() throws Exception {
        postJson("/api/orders", "").andExpect(problem(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("R8 검증 실패(400)는 Problem Details이며 필드별 errors를 담는다")
    void validationFailure() throws Exception {
        postJson("/api/products", """
                {"name":" ","price":0,"stock":-1}
                """)
                .andExpect(problem(HttpStatus.BAD_REQUEST))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.price").exists())
                .andExpect(jsonPath("$.errors.stock").exists());
    }

    @Test
    @DisplayName("R8 404 응답은 Problem Details")
    void notFound() throws Exception {
        mvc.perform(get("/api/products/{id}", 404)).andExpect(problem(HttpStatus.NOT_FOUND));
        mvc.perform(get("/api/orders/{id}", 404)).andExpect(problem(HttpStatus.NOT_FOUND));
        mvc.perform(post("/api/orders/{id}/cancel", 404)).andExpect(problem(HttpStatus.NOT_FOUND));
        postJson("/api/orders", orderBody(404, 1)).andExpect(problem(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("R8 409 응답은 Problem Details")
    void conflict() throws Exception {
        long product = createProduct("상품", 1000, 1);
        postJson("/api/orders", orderBody(product, 2)).andExpect(problem(HttpStatus.CONFLICT));

        long orderId = createOrder(product, 1);
        mvc.perform(post("/api/orders/{id}/cancel", orderId));
        mvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(problem(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("R8 목록 파라미터 범위 위반(400)은 Problem Details")
    void invalidPageParams() throws Exception {
        mvc.perform(get("/api/orders").param("size", "0")).andExpect(problem(HttpStatus.BAD_REQUEST));
    }
}
