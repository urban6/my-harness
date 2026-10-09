package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R8 배송")
class R08ShippingTest extends AbstractIntegrationTest {

    private long productId;

    private ApiResponse paidOrder() {
        productId = newProduct(1_000, 10);
        return newPaidOrder(uniqueUser(), null, productId, 2);
    }

    @Test
    @DisplayName("R8.1 PAID -> ship -> 200 SHIPPED, R3.5 형태, paidAt 유지, 재고 불변")
    void ship_paid_becomesShipped() {
        ApiResponse paid = paidOrder();

        ApiResponse r = ship(paid.id());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(r.json("status").asText()).isEqualTo("SHIPPED");
        assertThat(instant(r.json("paidAt"))).isEqualTo(instant(paid.json("paidAt")));
        assertThat(statusOf(paid.id())).isEqualTo("SHIPPED");
        assertThat(stockOf(productId)).isEqualTo(8);
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("R8.1 SHIPPED -> deliver -> 200 DELIVERED")
    void deliver_shipped_becomesDelivered() {
        ApiResponse paid = paidOrder();
        ship(paid.id());

        ApiResponse r = deliver(paid.id());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json("status").asText()).isEqualTo("DELIVERED");
        assertThat(statusOf(paid.id())).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("R8.2 PENDING_PAYMENT 주문 ship / deliver -> 409 INVALID_STATE")
    void pending_shipAndDeliver_409() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);

        ApiResponse s = ship(order.id());
        ApiResponse d = deliver(order.id());

        assertThat(s.status()).isEqualTo(409);
        assertThat(s.code()).isEqualTo("INVALID_STATE");
        assertThat(d.status()).isEqualTo(409);
        assertThat(d.code()).isEqualTo("INVALID_STATE");
        assertThat(statusOf(order.id())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R8.2 PAID 주문 deliver(ship 건너뛰기) -> 409")
    void deliver_paid_409() {
        ApiResponse paid = paidOrder();

        ApiResponse r = deliver(paid.id());

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(statusOf(paid.id())).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R8.2 SHIPPED 주문 재ship -> 409, DELIVERED 주문 ship/deliver -> 409")
    void ship_alreadyShippedOrDelivered_409() {
        ApiResponse paid = paidOrder();
        ship(paid.id());

        ApiResponse reship = ship(paid.id());
        deliver(paid.id());
        ApiResponse shipAfterDelivered = ship(paid.id());
        ApiResponse redeliver = deliver(paid.id());

        assertThat(reship.status()).isEqualTo(409);
        assertThat(reship.code()).isEqualTo("INVALID_STATE");
        assertThat(shipAfterDelivered.status()).isEqualTo(409);
        assertThat(redeliver.status()).isEqualTo(409);
        assertThat(redeliver.code()).isEqualTo("INVALID_STATE");
        assertThat(statusOf(paid.id())).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("R8.2 CANCELLED / PAYMENT_FAILED 주문 ship / deliver -> 409")
    void cancelledOrFailed_ship_409() {
        long p = newProduct(1_000, 10);
        ApiResponse cancelled = newOrder(uniqueUser(), null, p, 1);
        cancel(cancelled.id());
        ApiResponse failed = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("DECLINED", "pay-no");
        pay(failed.id(), uniqueKey(), "tok");

        assertThat(ship(cancelled.id()).status()).isEqualTo(409);
        assertThat(deliver(cancelled.id()).status()).isEqualTo(409);
        assertThat(ship(failed.id()).status()).isEqualTo(409);
        assertThat(deliver(failed.id()).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("R8.2 없는 주문 ship / deliver -> 404 ORDER_NOT_FOUND")
    void unknownOrder_404() {
        ApiResponse s = ship(Long.MAX_VALUE - 1);
        ApiResponse d = deliver(Long.MAX_VALUE - 1);

        assertThat(s.status()).isEqualTo(404);
        assertThat(s.code()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(d.status()).isEqualTo(404);
        assertThat(d.code()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R8.1 숫자가 아닌 id -> 400")
    void nonNumericId_400() {
        assertThat(post("/api/orders/abc/ship", null, null).status()).isEqualTo(400);
        assertThat(post("/api/orders/abc/deliver", null, null).status()).isEqualTo(400);
    }
}
