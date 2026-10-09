package com.example.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.order.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R6 주문 목록")
class OrderListApiTest extends ApiTestSupport {

    private List<Long> createOrders(int count) {
        long p = createProduct("a", 100, 1000);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(createOrder(List.of(item(p, 1))).getBody().get("id").asLong());
        }
        return ids;
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(o -> ids.add(o.get("id").asLong()));
        return ids;
    }

    @Test
    void list_usesDefaultPageAndSize() {
        createOrders(25);

        ResponseEntity<JsonNode> res = get("/api/orders");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = res.getBody();
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("totalElements").asLong()).isEqualTo(25);
        assertThat(body.get("content")).hasSize(20);
    }

    @Test
    void list_returnsRequestedPage_withElementsShapedLikeOrderDetail() {
        List<Long> created = createOrders(5);

        JsonNode body = get("/api/orders?page=1&size=2").getBody();

        assertThat(body.get("page").asInt()).isEqualTo(1);
        assertThat(body.get("size").asInt()).isEqualTo(2);
        assertThat(body.get("totalElements").asLong()).isEqualTo(5);
        // 최신순: 5,4 | 3,2 | 1
        assertThat(ids(body)).containsExactly(created.get(2), created.get(1));

        JsonNode first = body.get("content").get(0);
        assertThat(first).isEqualTo(get("/api/orders/" + first.get("id").asLong()).getBody());
    }

    @Test
    void list_sortsByCreatedAtDesc_thenIdDesc() {
        List<Long> created = createOrders(4);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        // id1 이 가장 최근, id2·id3 은 같은 시각, id4 가 가장 오래됨
        setCreatedAt(created.get(0), base.plusSeconds(10));
        setCreatedAt(created.get(1), base.plusSeconds(5));
        setCreatedAt(created.get(2), base.plusSeconds(5));
        setCreatedAt(created.get(3), base);

        JsonNode body = get("/api/orders?size=100").getBody();

        assertThat(ids(body)).containsExactly(created.get(0), created.get(2), created.get(1), created.get(3));
    }

    @Test
    void list_acceptsBoundaryValues() {
        createOrders(1);

        assertThat(get("/api/orders?page=0&size=1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/orders?size=100").getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode beyond = get("/api/orders?page=5&size=10").getBody();
        assertThat(beyond.get("content")).isEmpty();
        assertThat(beyond.get("totalElements").asLong()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "size=-5", "page=abc", "size=1.5"})
    void list_returns400_whenPageOrSizeOutOfRange(String query) {
        assertProblem(get("/api/orders?" + query), HttpStatus.BAD_REQUEST);
    }

    private void setCreatedAt(long orderId, Instant createdAt) {
        jdbc.update("update orders set created_at = ? where id = ?", Timestamp.from(createdAt), orderId);
    }
}
