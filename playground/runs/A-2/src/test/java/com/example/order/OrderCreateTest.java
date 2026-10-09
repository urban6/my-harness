package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("R3 주문 생성")
class OrderCreateTest extends IntegrationTestSupport {

    @Test
    void createsOrderAndDeductsStock() {
        long keyboard = createProduct("Keyboard", 30000, 5);
        long mouse = createProduct("Mouse", 10000, 10);

        ResponseEntity<String> response = createOrder(item(keyboard, 2), item(mouse, 3));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json(response);
        long id = body.get("id").asLong();
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/api/orders/" + id);
        assertThat(body.get("status").asText()).isEqualTo("ORDERED");
        assertThat(body.get("totalPrice").asLong()).isEqualTo(2 * 30000 + 3 * 10000);
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("createdAt").asText()).isNotBlank();

        assertThat(stockOf(keyboard)).isEqualTo(3);
        assertThat(stockOf(mouse)).isEqualTo(7);

        // The body has the same shape as R4.
        assertThat(json(get("/api/orders/" + id))).isEqualTo(body);
    }

    @Test
    void allowsOrderingExactlyTheRemainingStock() {
        long product = createProduct("Last one", 1000, 2);

        assertThat(createOrder(item(product, 2)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockOf(product)).isZero();
    }

    @Test
    void rejectsEmptyOrMissingItems() {
        assertProblem(post("/api/orders", Map.of("items", List.of())), HttpStatus.BAD_REQUEST);
        assertProblem(post("/api/orders", Map.of()), HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsQuantityBelowOne() {
        long product = createProduct("A", 1000, 10);

        assertProblem(createOrder(item(product, 0)), HttpStatus.BAD_REQUEST);
        assertProblem(createOrder(item(product, -1)), HttpStatus.BAD_REQUEST);
        assertProblem(post("/api/orders", Map.of("items", List.of(Map.of("productId", product)))),
                HttpStatus.BAD_REQUEST);
        assertThat(stockOf(product)).isEqualTo(10);
    }

    @Test
    void rejectsMissingProductIdOrNullItem() {
        assertProblem(post("/api/orders", Map.of("items", List.of(Map.of("quantity", 1)))), HttpStatus.BAD_REQUEST);
        assertProblem(post("/api/orders", "{\"items\":[null]}"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsDuplicateProductIds() {
        long product = createProduct("A", 1000, 10);

        assertProblem(createOrder(item(product, 1), item(product, 2)), HttpStatus.BAD_REQUEST);
        assertThat(stockOf(product)).isEqualTo(10);
    }

    @Test
    void returns404ForUnknownProductWithoutDeductingStock() {
        long product = createProduct("A", 1000, 10);

        assertProblem(createOrder(item(product, 1), item(999999, 1)), HttpStatus.NOT_FOUND);
        assertThat(stockOf(product)).isEqualTo(10);
        assertThat(orderCount()).isZero();
    }

    @Test
    void returns409WhenStockIsInsufficient() {
        long product = createProduct("A", 1000, 1);

        assertProblem(createOrder(item(product, 2)), HttpStatus.CONFLICT);
        assertThat(stockOf(product)).isEqualTo(1);
        assertThat(orderCount()).isZero();
    }

    @Test
    void deductsNothingWhenAnyItemLacksStock() {
        long plenty = createProduct("Plenty", 1000, 100);
        long scarce = createProduct("Scarce", 2000, 1);

        assertProblem(createOrder(item(plenty, 5), item(scarce, 2)), HttpStatus.CONFLICT);

        assertThat(stockOf(plenty)).isEqualTo(100);
        assertThat(stockOf(scarce)).isEqualTo(1);
        assertThat(orderCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
    }

    @Test
    void reports400BeforeOtherErrors() {
        long scarce = createProduct("Scarce", 1000, 1);

        // duplicate (400) + unknown product (404) + insufficient stock (409)
        assertProblem(createOrder(item(scarce, 5), item(scarce, 5), item(999999, 1)), HttpStatus.BAD_REQUEST);
        // invalid quantity (400) + unknown product (404)
        assertProblem(createOrder(item(999999, 0)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void reports404BeforeConflict() {
        long scarce = createProduct("Scarce", 1000, 1);

        assertProblem(createOrder(item(scarce, 5), item(999999, 1)), HttpStatus.NOT_FOUND);
        assertThat(stockOf(scarce)).isEqualTo(1);
    }

    @Test
    void rejectsNullItemsField() {
        Map<String, Object> body = new HashMap<>();
        body.put("items", null);
        assertProblem(post("/api/orders", body), HttpStatus.BAD_REQUEST);
    }

    private long orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
    }
}
