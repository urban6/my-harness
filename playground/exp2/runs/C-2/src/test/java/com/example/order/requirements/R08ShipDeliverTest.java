package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** R8. 배송 (EXPIRED 상태의 거부는 R06ExpirationTest) */
class R08ShipDeliverTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R8.1 PAID 주문 ship은 200 SHIPPED이고 본문은 R3.5 형태·이후 조회와 같다")
    void r8_1_ship_paidToShipped() {
        long orderId = newOrderInStatus("PAID");

        ApiResponse r = ship(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("SHIPPED");
        assertThat(r.json().fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(r.text("paidAt")).isNotNull();
        assertThat(getOrder(orderId).json()).isEqualTo(r.json());
    }

    @Test
    @DisplayName("R8.1 SHIPPED 주문 deliver는 200 DELIVERED이고 본문은 R3.5 형태·이후 조회와 같다")
    void r8_1_deliver_shippedToDelivered() {
        long orderId = newOrderInStatus("SHIPPED");

        ApiResponse r = deliver(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("DELIVERED");
        assertThat(getOrder(orderId).json()).isEqualTo(r.json());
    }

    @Test
    @DisplayName("R8.1 배송·배송 완료는 재고와 쿠폰 사용을 바꾸지 않는다")
    void r8_1_shipAndDeliver_doNotChangeStockOrCoupon() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(productId, 3)).id();
        pay(orderId);

        ship(orderId);
        deliver(orderId);

        assertThat(stock(productId)).isEqualTo(7);
        assertThat(reserved(productId)).isZero();
        assertThat(usedCount(coupon)).isEqualTo(1);
    }

    @ParameterizedTest(name = "R8.2 {0} 주문은 ship할 수 없다")
    @ValueSource(strings = {"PENDING_PAYMENT", "PAYMENT_FAILED", "CANCELLED", "SHIPPED", "DELIVERED", "REFUNDED"})
    @DisplayName("R8.2 PAID가 아닌 주문 ship은 409 INVALID_STATE이고 상태가 변하지 않는다")
    void r8_2_ship_otherStates_return409(String status) {
        long orderId = newOrderInStatus(status);

        ApiResponse r = ship(orderId);

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(getOrder(orderId).text("status")).isEqualTo(status);
    }

    @ParameterizedTest(name = "R8.2 {0} 주문은 deliver할 수 없다")
    @ValueSource(strings = {"PENDING_PAYMENT", "PAYMENT_FAILED", "CANCELLED", "PAID", "DELIVERED", "REFUNDED"})
    @DisplayName("R8.2 SHIPPED가 아닌 주문 deliver는 409 INVALID_STATE이고 상태가 변하지 않는다")
    void r8_2_deliver_otherStates_return409(String status) {
        long orderId = newOrderInStatus(status);

        ApiResponse r = deliver(orderId);

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(getOrder(orderId).text("status")).isEqualTo(status);
    }

    @Test
    @DisplayName("R8.2 없는 주문 ship·deliver는 404 ORDER_NOT_FOUND이다")
    void r8_2_unknownOrder_returns404() {
        assertProblem(ship(987_654_321L), 404, "ORDER_NOT_FOUND");
        assertProblem(deliver(987_654_321L), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R8.2 같은 ship을 두 번 호출하면 두 번째는 409이다")
    void r8_2_shipTwice_secondIs409() {
        long orderId = newOrderInStatus("PAID");

        assertThat(ship(orderId).status()).isEqualTo(200);

        assertProblem(ship(orderId), 409, "INVALID_STATE");
    }
}
