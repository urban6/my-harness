package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class OrderApiSmokeTest extends AbstractApiTest {

    @Test
    void createOrder_decreasesStock_andGetReturnsSameShape() {
        long p1 = createProduct("키보드", 35000, 10);
        long p2 = createProduct("마우스", 12000, 5);

        ResponseEntity<JsonNode> created = createOrder(List.of(item(p1, 2), item(p2, 1)));

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode body = created.getBody();
        long orderId = body.get("id").asLong();
        assertThat(locationOf(created).getPath()).isEqualTo("/api/orders/" + orderId);
        assertThat(body.get("status").asText()).isEqualTo("ORDERED");
        assertThat(body.get("totalPrice").asLong()).isEqualTo(35000 * 2 + 12000);
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("productId").asLong()).isEqualTo(p1);
        assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(35000);
        assertThat(body.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(stockOf(p1)).isEqualTo(8);
        assertThat(stockOf(p2)).isEqualTo(4);

        ResponseEntity<JsonNode> fetched = get("/api/orders/" + orderId);
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody()).isEqualTo(body);
    }

    @Test
    void createOrder_insufficientStock_returns409_andNothingIsDecreased() {
        long ok = createProduct("충분", 1000, 5);
        long low = createProduct("부족", 1000, 1);

        ResponseEntity<JsonNode> res = createOrder(List.of(item(ok, 2), item(low, 2)));

        assertProblem(res, 409, "insufficient-stock");
        assertThat(stockOf(ok)).isEqualTo(5);
        assertThat(stockOf(low)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    void createOrder_unknownProduct_returns404_evenWhenOtherItemHasInsufficientStock() {
        long low = createProduct("부족", 1000, 1);
        assertProblem(createOrder(List.of(item(low, 5), item(9999, 1))), 404, "product-not-found");
    }

    @Test
    void createOrder_invalidQuantity_returns400() {
        long p = createProduct("상품", 1000, 5);
        assertProblem(createOrder(List.of(item(p, 0))), 400, "validation-error");
    }

    @Test
    void cancelOrder_restoresStock_andSecondCancelReturns409() {
        long p = createProduct("상품", 1000, 5);
        long orderId = createOrder(List.of(item(p, 3))).getBody().get("id").asLong();
        assertThat(stockOf(p)).isEqualTo(2);

        ResponseEntity<JsonNode> cancelled = postNoBody("/api/orders/" + orderId + "/cancel");

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.getBody().get("totalPrice").asLong()).isEqualTo(3000);
        assertThat(stockOf(p)).isEqualTo(5);

        assertProblem(postNoBody("/api/orders/" + orderId + "/cancel"), 409, "order-already-cancelled");
        assertThat(stockOf(p)).isEqualTo(5);
        assertProblem(postNoBody("/api/orders/9999/cancel"), 404, "order-not-found");
    }

    @Test
    void getOrder_unknownId_returns404Problem() {
        assertProblem(get("/api/orders/9999"), 404, "order-not-found");
    }

    @Test
    void listOrders_sortedByCreatedAtDescThenIdDesc_withPageEnvelope() {
        long p = createProduct("상품", 1000, 100);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(createOrder(List.of(item(p, 1))).getBody().get("id").asLong());
        }

        ResponseEntity<JsonNode> res = get("/api/orders?page=0&size=2");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = res.getBody();
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(2);
        assertThat(body.get("totalElements").asLong()).isEqualTo(3);
        assertThat(body.get("content")).hasSize(2);
        assertThat(body.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(2));
        assertThat(body.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(1));
    }

    @Test
    void listOrders_outOfRangeParams_return400() {
        assertProblem(get("/api/orders?size=101"), 400, "validation-error");
        assertProblem(get("/api/orders?page=-1"), 400, "validation-error");
        assertThat(get("/api/orders").getBody().get("size").asInt()).isEqualTo(20);
    }

    @Test
    void concurrentOrders_stock10_20requests_exactly10Succeed() throws Exception {
        long p = createProduct("한정판", 1000, 10);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return createOrder(List.of(item(p, 1))).getStatusCode().value();
            }));
        }
        ready.await();
        go.countDown();
        int created = 0;
        int conflict = 0;
        for (Future<Integer> f : futures) {
            int code = f.get();
            if (code == 201) created++;
            else if (code == 409) conflict++;
        }
        pool.shutdown();

        assertThat(created).isEqualTo(10);
        assertThat(conflict).isEqualTo(10);
        assertThat(stockOf(p)).isZero();
    }
}
