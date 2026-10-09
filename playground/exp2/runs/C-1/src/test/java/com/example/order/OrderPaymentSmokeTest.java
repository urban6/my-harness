package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OrderPaymentSmokeTest extends IntegrationTestBase {

    @Test
    void createOrderThenPay_approved() {
        JsonNode product = createProduct("키보드", 15000, 10);
        long productId = product.get("id").asLong();

        ResponseEntity<JsonNode> order = createOrder("user-1", "order-key-1",
                Map.of("items", List.of(Map.of("productId", productId, "quantity", 2))));

        assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(order.getHeaders().getLocation()).isNotNull();
        assertThat(order.getBody().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.getBody().get("totalPrice").asLong()).isEqualTo(30000);
        assertThat(get("/api/products/" + productId).getBody().get("reserved").asInt()).isEqualTo(2);

        long orderId = order.getBody().get("id").asLong();
        ResponseEntity<JsonNode> paid = payOrder(orderId, "pay-key-1", "tok_visa");

        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(paid.getBody().get("paidAt").isNull()).isFalse();
        assertThat(PG.payCallCount()).isEqualTo(1);
        assertThat(PG.lastPayRequest().idempotencyKey()).isEqualTo("pay-key-1");
        assertThat(PG.lastPayRequest().body()).contains("tok_visa").contains("30000");

        JsonNode after = get("/api/products/" + productId).getBody();
        assertThat(after.get("stock").asInt()).isEqualTo(8);
        assertThat(after.get("reserved").asInt()).isZero();
    }

    @Test
    void pay_declined_returns402_andRestoresReservation() {
        long productId = createProduct("마우스", 5000, 3).get("id").asLong();
        long orderId = createOrder("user-1", "order-key-2",
                Map.of("items", List.of(Map.of("productId", productId, "quantity", 1)))).getBody().get("id").asLong();
        PG.decline();

        ResponseEntity<JsonNode> res = payOrder(orderId, "pay-key-2", "tok_declined");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(res.getBody().get("code").asText()).isEqualTo("PAYMENT_DECLINED");
        assertThat(get("/api/orders/" + orderId).getBody().get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(get("/api/products/" + productId).getBody().get("reserved").asInt()).isZero();
    }

    @Test
    void createOrder_sameKeyReplaysFirstResponse() {
        long productId = createProduct("모니터", 100000, 5).get("id").asLong();
        Map<String, Object> body = Map.of("items", List.of(Map.of("productId", productId, "quantity", 1)));

        ResponseEntity<JsonNode> first = createOrder("user-1", "same-key", body);
        ResponseEntity<JsonNode> second = createOrder("user-1", "same-key", body);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("id").asLong()).isEqualTo(first.getBody().get("id").asLong());
        assertThat(get("/api/products/" + productId).getBody().get("reserved").asInt()).isEqualTo(1);
    }
}
