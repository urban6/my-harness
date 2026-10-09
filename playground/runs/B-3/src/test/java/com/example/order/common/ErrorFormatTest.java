package com.example.order.common;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.order.support.IntegrationTest;

/**
 * R8: R1~R7의 모든 에러 응답과 JSON 파싱 실패가 RFC 9457 Problem Details
 * (application/problem+json, type·title·status·detail)로 내려오는지 확인한다.
 */
@DisplayName("R8 에러 포맷")
class ErrorFormatTest extends IntegrationTest {

    @Test
    void malformedJson_returns400Problem() throws Exception {
        postJson("/api/products", "{\"name\":\"a\",").andExpect(problem(400));
        postJson("/api/orders", "{items: oops}").andExpect(problem(400));
        postJson("/api/orders", "").andExpect(problem(400));
    }

    @Test
    void wrongJsonType_returns400Problem() throws Exception {
        postJson("/api/products", "{\"name\":\"a\",\"price\":\"many\",\"stock\":1}").andExpect(problem(400));
        postJson("/api/orders", "{\"items\":{\"productId\":1}}").andExpect(problem(400));
    }

    @Test
    void productErrors_areProblems() throws Exception {
        postJson("/api/products", "{\"name\":\" \",\"price\":0,\"stock\":-1}")
                .andExpect(problem(400))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.price").exists())
                .andExpect(jsonPath("$.errors.stock").exists());
        getJson("/api/products/12345").andExpect(problem(404));
    }

    @Test
    void orderErrors_areProblems() throws Exception {
        long product = createProduct("A", 1000, 1);

        postJson("/api/orders", "{\"items\":[]}").andExpect(problem(400));
        postJson("/api/orders", "{\"items\":[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":1}]}"
                .formatted(product, product)).andExpect(problem(400));
        postJson("/api/orders", "{\"items\":[{\"productId\":777,\"quantity\":1}]}").andExpect(problem(404));
        postJson("/api/orders", "{\"items\":[{\"productId\":%d,\"quantity\":2}]}".formatted(product))
                .andExpect(problem(409));

        getJson("/api/orders/777").andExpect(problem(404));
        getJson("/api/orders?size=0").andExpect(problem(400));

        long orderId = createOrder("[{\"productId\":%d,\"quantity\":1}]".formatted(product)).get("id").asLong();
        postJson("/api/orders/777/cancel", "").andExpect(problem(404));
        postJson("/api/orders/" + orderId + "/cancel", "").andExpect(jsonPath("$.status").value("CANCELLED"));
        postJson("/api/orders/" + orderId + "/cancel", "").andExpect(problem(409));
    }

    @Test
    void invalidPathVariable_returns400Problem() throws Exception {
        getJson("/api/orders/abc").andExpect(problem(400));
        getJson("/api/products/abc").andExpect(problem(400));
    }
}
