package com.example.order;

import java.util.List;

import com.example.order.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R5 주문 취소")
class OrderCancelApiTest extends ApiTestSupport {

    @Test
    void cancel_returns200Cancelled_andRestoresStock() {
        long a = createProduct("a", 100, 10);
        long b = createProduct("b", 200, 5);
        JsonNode created = createOrder(List.of(item(a, 4), item(b, 5))).getBody();
        long id = created.get("id").asLong();
        assertThat(stockOf(a)).isEqualTo(6);
        assertThat(stockOf(b)).isZero();

        ResponseEntity<JsonNode> res = post("/api/orders/" + id + "/cancel", null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = res.getBody();
        assertThat(body.get("id").asLong()).isEqualTo(id);
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("totalPrice")).isEqualTo(created.get("totalPrice"));
        assertThat(body.get("items")).isEqualTo(created.get("items"));
        assertThat(body.get("createdAt")).isEqualTo(created.get("createdAt"));

        assertThat(get("/api/orders/" + id).getBody()).isEqualTo(body);
        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(stockOf(b)).isEqualTo(5);
    }

    @Test
    void cancel_returns409_whenAlreadyCancelled_andDoesNotRestoreTwice() {
        long p = createProduct("a", 100, 10);
        long id = createOrder(List.of(item(p, 3))).getBody().get("id").asLong();
        post("/api/orders/" + id + "/cancel", null);

        assertProblem(post("/api/orders/" + id + "/cancel", null), HttpStatus.CONFLICT);
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    void cancel_returns404_whenOrderMissing() {
        assertProblem(post("/api/orders/999999/cancel", null), HttpStatus.NOT_FOUND);
    }
}
