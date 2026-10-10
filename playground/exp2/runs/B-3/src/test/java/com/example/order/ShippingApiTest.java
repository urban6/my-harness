package com.example.order;

import static com.example.order.support.TestApi.newUserId;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R8 배송")
class ShippingApiTest extends IntegrationTest {

    @Test
    @DisplayName("R8.1 PAID → ship → SHIPPED → deliver → DELIVERED, 200 + 주문 본문")
    void shipAndDeliver() {
        long productId = api.createProduct(1_000, 10);
        long orderId = api.createOrderId(newUserId(), productId, 2);
        api.pay(orderId).assertStatus(200);

        JsonNode shipped = api.ship(orderId).assertStatus(200).body();
        assertThat(shipped.get("id").asLong()).isEqualTo(orderId);
        assertThat(shipped.get("status").asText()).isEqualTo("SHIPPED");
        assertThat(shipped.get("paidAt").isNull()).isFalse();

        JsonNode delivered = api.deliver(orderId).assertStatus(200).body();
        assertThat(delivered.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(api.order(orderId)).isEqualTo(delivered);
        assertThat(api.product(productId).get("stock").asInt()).isEqualTo(8);
    }

    @Test
    @DisplayName("R8.2 그 밖의 상태 → 409 INVALID_STATE, 없으면 404")
    void invalidTransitions() {
        long productId = api.createProduct(1_000, 10);
        long pending = api.createOrderId(newUserId(), productId, 1);
        long paid = api.createOrderId(newUserId(), productId, 1);
        api.pay(paid).assertStatus(200);
        long delivered = api.createOrderId(newUserId(), productId, 1);
        api.pay(delivered).assertStatus(200);
        api.ship(delivered).assertStatus(200);
        api.deliver(delivered).assertStatus(200);

        api.ship(pending).assertProblem(409, "INVALID_STATE");
        api.deliver(pending).assertProblem(409, "INVALID_STATE");
        api.deliver(paid).assertProblem(409, "INVALID_STATE");
        api.ship(delivered).assertProblem(409, "INVALID_STATE");
        api.deliver(delivered).assertProblem(409, "INVALID_STATE");
        assertThat(api.order(pending).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(api.order(paid).get("status").asText()).isEqualTo("PAID");

        api.ship(999_999_999L).assertProblem(404, "ORDER_NOT_FOUND");
        api.deliver(999_999_999L).assertProblem(404, "ORDER_NOT_FOUND");
    }
}
