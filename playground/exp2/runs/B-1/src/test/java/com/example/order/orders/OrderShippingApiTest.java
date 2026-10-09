package com.example.order.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R8 배송")
class OrderShippingApiTest extends IntegrationTest {

    @Test
    @DisplayName("R8.1 PAID → ship → SHIPPED → deliver → DELIVERED, 둘 다 200 + 주문 본문")
    void shipAndDeliver() {
        long productId = createProduct(1_000, 10);
        long orderId = createOrder(uniqueUser(), null, List.of(item(productId, 2)));
        payOk(orderId);

        ApiResponse shipped = action(orderId, "ship");
        assertThat(shipped.status()).isEqualTo(200);
        assertThat(shipped.body().get("status").asText()).isEqualTo("SHIPPED");
        assertThat(shipped.body()).isEqualTo(order(orderId));

        ApiResponse delivered = action(orderId, "deliver");
        assertThat(delivered.status()).isEqualTo(200);
        assertThat(delivered.body().get("status").asText()).isEqualTo("DELIVERED");
        assertThat(delivered.body().hasNonNull("paidAt")).isTrue();
        assertProduct(productId, 8, 0); // 배송은 재고를 바꾸지 않는다
    }

    @Test
    @DisplayName("R8.2 ship 은 PAID 에서만, deliver 는 SHIPPED 에서만: 그 밖은 409 INVALID_STATE")
    void invalidTransitions() {
        long productId = createProduct(1_000, 10);
        long pending = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        assertProblem(action(pending, "ship"), 409, "INVALID_STATE");
        assertProblem(action(pending, "deliver"), 409, "INVALID_STATE");

        long paid = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        payOk(paid);
        assertProblem(action(paid, "deliver"), 409, "INVALID_STATE");

        action(paid, "ship");
        assertProblem(action(paid, "ship"), 409, "INVALID_STATE");

        action(paid, "deliver");
        assertProblem(action(paid, "ship"), 409, "INVALID_STATE");
        assertProblem(action(paid, "deliver"), 409, "INVALID_STATE");

        long cancelled = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        action(cancelled, "cancel");
        assertProblem(action(cancelled, "ship"), 409, "INVALID_STATE");

        assertThat(orderStatus(pending)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R8.2 없는 주문은 404")
    void notFound() {
        assertProblem(action(999_999_999L, "ship"), 404, "ORDER_NOT_FOUND");
        assertProblem(action(999_999_999L, "deliver"), 404, "ORDER_NOT_FOUND");
    }
}
