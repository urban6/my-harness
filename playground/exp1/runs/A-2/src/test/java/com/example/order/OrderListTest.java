package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("R6 주문 목록")
class OrderListTest extends IntegrationTestSupport {

    @Test
    void returnsNewestFirstWithDefaultPaging() {
        long product = createProduct("A", 1000, 100);
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(createOrderId(item(product, i + 1)));
        }

        ResponseEntity<String> response = get("/api/orders");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("totalElements").asLong()).isEqualTo(3);
        assertThat(ids(body)).containsExactly(created.get(2), created.get(1), created.get(0));

        // Each element has the same shape as R4.
        for (JsonNode order : body.get("content")) {
            assertThat(order).isEqualTo(json(get("/api/orders/" + order.get("id").asLong())));
        }
    }

    @Test
    void ordersByCreatedAtDescThenIdDesc() {
        long product = createProduct("A", 1000, 100);
        long first = createOrderId(item(product, 1));
        long second = createOrderId(item(product, 1));
        long third = createOrderId(item(product, 1));
        long fourth = createOrderId(item(product, 1));

        Timestamp older = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp newer = Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"));
        // first and third share the newest timestamp, so id breaks the tie; fourth is the oldest.
        jdbc.update("UPDATE orders SET created_at = ? WHERE id IN (?, ?)", newer, first, third);
        jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?", older, second);
        jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2025-12-31T00:00:00Z")), fourth);

        assertThat(ids(json(get("/api/orders")))).containsExactly(third, first, second, fourth);
    }

    @Test
    void paginates() {
        long product = createProduct("A", 1000, 100);
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(createOrderId(item(product, 1)));
        }

        JsonNode page0 = json(get("/api/orders?page=0&size=2"));
        JsonNode page1 = json(get("/api/orders?page=1&size=2"));
        JsonNode page2 = json(get("/api/orders?page=2&size=2"));
        JsonNode page3 = json(get("/api/orders?page=3&size=2"));

        assertThat(ids(page0)).containsExactly(created.get(4), created.get(3));
        assertThat(ids(page1)).containsExactly(created.get(2), created.get(1));
        assertThat(ids(page2)).containsExactly(created.get(0));
        assertThat(ids(page3)).isEmpty();
        assertThat(page1.get("page").asInt()).isEqualTo(1);
        assertThat(page1.get("size").asInt()).isEqualTo(2);
        assertThat(page1.get("totalElements").asLong()).isEqualTo(5);
    }

    @Test
    void returnsEmptyPageWhenNoOrders() {
        JsonNode body = json(get("/api/orders"));
        assertThat(body.get("content")).isEmpty();
        assertThat(body.get("totalElements").asLong()).isZero();
    }

    @Test
    void acceptsBoundarySizes() {
        assertThat(get("/api/orders?size=1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/orders?size=100").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsOutOfRangeParameters() {
        assertProblem(get("/api/orders?page=-1"), HttpStatus.BAD_REQUEST);
        assertProblem(get("/api/orders?size=0"), HttpStatus.BAD_REQUEST);
        assertProblem(get("/api/orders?size=101"), HttpStatus.BAD_REQUEST);
        assertProblem(get("/api/orders?page=abc"), HttpStatus.BAD_REQUEST);
        assertProblem(get("/api/orders?size=xyz"), HttpStatus.BAD_REQUEST);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(order -> ids.add(order.get("id").asLong()));
        return ids;
    }
}
