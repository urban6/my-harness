package com.example.order.orders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.order.support.IntegrationTest;

@DisplayName("R6 주문 목록")
class OrderListApiTest extends IntegrationTest {

    @Test
    void list_usesDefaults_andSortsByCreatedAtDesc() throws Exception {
        long product = createProduct("A", 1000, 100);
        for (int i = 1; i <= 3; i++) {
            createOrder("[{\"productId\":%d,\"quantity\":%d}]".formatted(product, i));
        }
        // 생성 순서와 다르게 createdAt을 조정해 정렬 기준이 id가 아닌 createdAt임을 확인한다.
        setCreatedAt(1, "2026-01-03T00:00:00Z");
        setCreatedAt(2, "2026-01-01T00:00:00Z");
        setCreatedAt(3, "2026-01-02T00:00:00Z");

        getJson("/api/orders")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[1].id").value(3))
                .andExpect(jsonPath("$.content[2].id").value(2))
                // 원소는 R4 응답과 같은 형태
                .andExpect(jsonPath("$.content[0].status").value("ORDERED"))
                .andExpect(jsonPath("$.content[0].totalPrice").value(1000))
                .andExpect(jsonPath("$.content[0].items[0].productId").value(product))
                .andExpect(jsonPath("$.content[0].items[0].quantity").value(1))
                .andExpect(jsonPath("$.content[0].items[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-01-03T00:00:00Z"));
    }

    @Test
    void list_breaksTiesByIdDesc() throws Exception {
        long product = createProduct("A", 1000, 100);
        for (int i = 0; i < 3; i++) {
            createOrder("[{\"productId\":%d,\"quantity\":1}]".formatted(product));
        }
        jdbcTemplate.update("UPDATE orders SET created_at = ?", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));

        getJson("/api/orders")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(3))
                .andExpect(jsonPath("$.content[1].id").value(2))
                .andExpect(jsonPath("$.content[2].id").value(1));
    }

    @Test
    void list_paginates() throws Exception {
        long product = createProduct("A", 1000, 100);
        for (int i = 0; i < 5; i++) {
            createOrder("[{\"productId\":%d,\"quantity\":1}]".formatted(product));
        }
        jdbcTemplate.update("UPDATE orders SET created_at = ?", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));

        getJson("/api/orders?page=1&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(3))
                .andExpect(jsonPath("$.content[1].id").value(2));

        getJson("/api/orders?page=2&size=2")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1));
    }

    @Test
    void list_acceptsBoundarySizes() throws Exception {
        getJson("/api/orders?size=1").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1));
        getJson("/api/orders?size=100").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
        getJson("/api/orders?page=0").andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "size=-5", "page=abc", "size=1.5"})
    void list_returns400_whenOutOfRange(String query) throws Exception {
        getJson("/api/orders?" + query).andExpect(problem(400));
    }

    private void setCreatedAt(long orderId, String iso) {
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?", Timestamp.from(Instant.parse(iso)), orderId);
    }
}
