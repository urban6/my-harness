package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api.Response;
import com.example.order.support.Concurrently;
import com.example.order.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R10 동시성")
class ConcurrencyApiTest extends IntegrationTest {

    private static long count(List<Response> responses, int status) {
        return responses.stream().filter(r -> r.status() == status).count();
    }

    @Test
    @DisplayName("R10.1 available 10인 상품에 수량 1 주문 20건 → 201 10건, 409 10건, reserved 10")
    void stock() {
        long p = createProduct(1000, 10);

        List<Response> responses = Concurrently.run(20,
                i -> postOrder(uniqueUser(), uniqueKey(), orderBody(null, p, 1)));

        assertThat(count(responses, 201)).isEqualTo(10);
        assertThat(count(responses, 409)).isEqualTo(10);
        responses.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertProblem(r, 409, "INSUFFICIENT_STOCK"));
        assertThat(product(p).get("reserved").asLong()).isEqualTo(10);
        assertThat(product(p).get("available").asLong()).isZero();
    }

    @Test
    @DisplayName("R10.2 totalQuantity 5인 쿠폰을 사용자 15명이 동시에 → 201 5건, 409 10건, usedCount 5")
    void coupon() {
        long p = createProduct(1000, 100);
        String code = createCoupon("FIXED", 100, 5);

        List<Response> responses = Concurrently.run(15,
                i -> postOrder(uniqueUser(), uniqueKey(), orderBody(code, p, 1)));

        assertThat(count(responses, 201)).isEqualTo(5);
        assertThat(count(responses, 409)).isEqualTo(10);
        responses.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertProblem(r, 409, "COUPON_EXHAUSTED"));
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(5);
        assertThat(product(p).get("reserved").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 주문 5건을 동시에 → 201 정확히 1건")
    void sameUserSameCoupon() {
        long p = createProduct(1000, 100);
        String code = createCoupon("FIXED", 100, 100);
        String user = uniqueUser();

        List<Response> responses = Concurrently.run(5,
                i -> postOrder(user, uniqueKey(), orderBody(code, p, 1)));

        assertThat(count(responses, 201)).isEqualTo(1);
        responses.stream().filter(r -> r.status() != 201)
                .forEach(r -> assertProblem(r, 409, "COUPON_NOT_APPLICABLE"));
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);
        assertThat(product(p).get("reserved").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("R10.4 [P,Q]와 [Q,P] 주문을 섞어 동시에 → 5xx 없이 모두 처리, reserved 정확")
    void noDeadlock() {
        long p = createProduct(1000, 1000);
        long q = createProduct(1000, 1000);

        List<Response> responses = Concurrently.run(40, i -> postOrder(uniqueUser(), uniqueKey(),
                i % 2 == 0 ? orderBody(null, p, 1, q, 2) : orderBody(null, q, 2, p, 1)));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.toString()).isEqualTo(201));
        assertThat(product(p).get("reserved").asLong()).isEqualTo(40);
        assertThat(product(q).get("reserved").asLong()).isEqualTo(80);
    }

    @Test
    @DisplayName("R10.4 재고가 모자란 상태에서 [P,Q]/[Q,P]를 섞어도 5xx 없이 예약 수량이 정확")
    void noDeadlockUnderContention() {
        long p = createProduct(1000, 15);
        long q = createProduct(1000, 15);

        List<Response> responses = Concurrently.run(30, i -> postOrder(uniqueUser(), uniqueKey(),
                i % 2 == 0 ? orderBody(null, p, 1, q, 1) : orderBody(null, q, 1, p, 1)));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.toString()).isIn(201, 409));
        assertThat(count(responses, 201)).isEqualTo(15);
        assertThat(product(p).get("reserved").asLong()).isEqualTo(15);
        assertThat(product(q).get("reserved").asLong()).isEqualTo(15);
    }

    @Test
    @DisplayName("R10.5 같은 주문에 결제가 동시에 와도(키는 다름) PG 결제 요청 최대 1번, 성공 1건")
    void concurrentPay() {
        long p = createProduct(1000, 10);
        long orderId = createOrder(uniqueUser(), null, p, 2).get("id").asLong();

        List<Response> responses = Concurrently.run(10, i -> pay(orderId, uniqueKey(), "tok"));

        assertThat(count(responses, 200)).isEqualTo(1);
        responses.stream().filter(r -> r.status() != 200).forEach(r -> assertProblem(r, 409, "INVALID_STATE"));
        assertThat(PG.paymentCallsFor(orderId)).hasSize(1);
        assertThat(product(p).get("stock").asLong()).isEqualTo(8);
        assertThat(product(p).get("reserved").asLong()).isZero();
    }

    @Test
    @DisplayName("R10.5 결제와 취소가 동시에 와도 상태와 재고가 일관된다")
    void concurrentPayAndCancel() {
        long p = createProduct(1000, 10);
        long orderId = createOrder(uniqueUser(), null, p, 2).get("id").asLong();

        List<Response> responses = Concurrently.run(2, i -> i == 0
                ? pay(orderId, uniqueKey(), "tok")
                : api.post("/api/orders/" + orderId + "/cancel", null));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(200, 409));
        String status = order(orderId).get("status").asText();
        assertThat(status).isIn("CANCELLED", "REFUNDED", "PAID");
        long stock = product(p).get("stock").asLong();
        long reserved = product(p).get("reserved").asLong();
        assertThat(reserved).isZero();
        assertThat(stock).isEqualTo(status.equals("PAID") ? 8 : 10);
    }
}
