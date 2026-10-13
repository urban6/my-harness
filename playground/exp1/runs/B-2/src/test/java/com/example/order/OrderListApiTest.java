package com.example.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

class OrderListApiTest extends IntegrationTest {

    @Test
    @DisplayName("R6 주문 목록: 기본값 page=0, size=20, 원소는 R4 형태, createdAt 내림차순")
    void list_usesDefaultsAndSortsLatestFirst() throws Exception {
        long product = createProduct("상품", 1000, 100);
        long first = createOrder(product, 1);
        long second = createOrder(product, 2);
        long third = createOrder(product, 3);

        mvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(third))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.content[2].id").value(first))
                .andExpect(jsonPath("$.content[0].status").value("ORDERED"))
                .andExpect(jsonPath("$.content[0].totalPrice").value(3000))
                .andExpect(jsonPath("$.content[0].items[0].productId").value(product))
                .andExpect(jsonPath("$.content[0].items[0].quantity").value(3))
                .andExpect(jsonPath("$.content[0].items[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.content[0].createdAt").isString());
    }

    @Test
    @DisplayName("R6 주문 목록: createdAt이 같으면 id 내림차순")
    void list_breaksTiesByIdDesc() throws Exception {
        Timestamp same = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp older = Timestamp.from(Instant.parse("2025-12-31T00:00:00Z"));
        jdbc.update("INSERT INTO orders (id, status, total_price, created_at) VALUES (1, 'ORDERED', 0, ?)", same);
        jdbc.update("INSERT INTO orders (id, status, total_price, created_at) VALUES (2, 'ORDERED', 0, ?)", older);
        jdbc.update("INSERT INTO orders (id, status, total_price, created_at) VALUES (3, 'ORDERED', 0, ?)", same);

        mvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(3))
                .andExpect(jsonPath("$.content[1].id").value(1))
                .andExpect(jsonPath("$.content[2].id").value(2));
    }

    @Test
    @DisplayName("R6 주문 목록: page·size로 나눠 조회")
    void list_paginates() throws Exception {
        long product = createProduct("상품", 1000, 100);
        for (int i = 0; i < 5; i++) {
            createOrder(product, 1);
        }

        mvc.perform(get("/api/orders").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(3))
                .andExpect(jsonPath("$.content[1].id").value(2));

        mvc.perform(get("/api/orders").param("page", "2").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1));

        mvc.perform(get("/api/orders").param("page", "0").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5));
    }

    @Test
    @DisplayName("R6 주문 목록: 주문이 없으면 빈 content")
    void list_returnsEmpty() throws Exception {
        mvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest(name = "page={0}, size={1}")
    @CsvSource({"-1, 20", "0, 0", "0, 101", "0, -5", "abc, 20"})
    @DisplayName("R6 주문 목록: page·size 범위 밖이면 400")
    void list_returns400_whenOutOfRange(String page, String size) throws Exception {
        mvc.perform(get("/api/orders").param("page", page).param("size", size))
                .andExpect(problem(HttpStatus.BAD_REQUEST));
    }
}
