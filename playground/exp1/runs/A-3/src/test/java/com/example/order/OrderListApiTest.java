package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R6 주문 목록")
class OrderListApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("기본값 page=0, size=20이며 응답은 {content[], page, size, totalElements}")
    void defaults() {
        long product = createProduct("A", 100, 1000);
        for (int i = 0; i < 25; i++) {
            createOrderId(product, 1);
        }

        ResponseEntity<String> response = get("/api/orders");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("totalElements").asLong()).isEqualTo(25);
        assertThat(body.get("content")).hasSize(20);
    }

    @Test
    @DisplayName("content 원소는 주문 조회 응답과 같은 형태다")
    void contentHasOrderShape() {
        long product = createProduct("A", 100, 10);
        long orderId = createOrderId(product, 2);

        JsonNode element = json(get("/api/orders")).get("content").get(0);

        assertThat(element).isEqualTo(json(get("/api/orders/" + orderId)));
    }

    @Test
    @DisplayName("page·size로 페이지를 나누고 createdAt 내림차순으로 정렬한다")
    void paginatesNewestFirst() {
        long product = createProduct("A", 100, 1000);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(createOrderId(product, 1));
        }
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        // ids[0] is the newest, ids[4] the oldest, independent of id order.
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?",
                    Timestamp.from(base.minusSeconds(i * 60L)), ids.get(i));
        }

        JsonNode first = json(get("/api/orders?page=0&size=2"));
        JsonNode second = json(get("/api/orders?page=1&size=2"));
        JsonNode third = json(get("/api/orders?page=2&size=2"));
        JsonNode beyond = json(get("/api/orders?page=3&size=2"));

        assertThat(idsOf(first)).containsExactly(ids.get(0), ids.get(1));
        assertThat(idsOf(second)).containsExactly(ids.get(2), ids.get(3));
        assertThat(idsOf(third)).containsExactly(ids.get(4));
        assertThat(idsOf(beyond)).isEmpty();
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("size").asInt()).isEqualTo(2);
        assertThat(second.get("totalElements").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("createdAt이 같으면 id 내림차순으로 정렬한다")
    void tieBreaksByIdDescending() {
        long product = createProduct("A", 100, 1000);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(createOrderId(product, 1));
        }
        jdbc.update("UPDATE orders SET created_at = ?", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));

        assertThat(idsOf(json(get("/api/orders?size=100"))))
                .containsExactly(ids.get(3), ids.get(2), ids.get(1), ids.get(0));
    }

    @Test
    @DisplayName("size 경계값 1과 100은 허용된다")
    void acceptsSizeBoundaries() {
        assertThat(get("/api/orders?size=1").getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/orders?size=100").getStatusCode().value()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "size=-5", "page=abc", "size=xyz"})
    @DisplayName("page·size가 범위 밖이면 400")
    void rejectsOutOfRange(String query) {
        assertProblem(get("/api/orders?" + query), 400);
    }

    private static List<Long> idsOf(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(order -> ids.add(order.get("id").asLong()));
        return ids;
    }
}
