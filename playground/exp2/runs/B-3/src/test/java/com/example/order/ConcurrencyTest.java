package com.example.order;

import static com.example.order.support.TestApi.coupon;
import static com.example.order.support.TestApi.item;
import static com.example.order.support.TestApi.newCouponCode;
import static com.example.order.support.TestApi.newKey;
import static com.example.order.support.TestApi.newUserId;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTest;
import com.example.order.support.TestApi.Response;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R10 동시성")
class ConcurrencyTest extends IntegrationTest {

    @Test
    @DisplayName("R10.1 available 10에 수량 1 주문 20건 동시 → 201 10건, 409 10건, reserved 10")
    void stockReservation() throws Exception {
        long productId = api.createProduct(1_000, 10);

        List<Response> responses = runConcurrently(IntStream.range(0, 20)
                .<Callable<Response>>mapToObj(i -> () -> api.createOrder(newUserId(), null, List.of(item(productId, 1))))
                .toList());

        assertThat(count(responses, 201)).isEqualTo(10);
        assertThat(count(responses, 409)).isEqualTo(10);
        responses.stream().filter(r -> r.status() == 409).forEach(r -> r.assertProblem(409, "INSUFFICIENT_STOCK"));
        assertThat(api.product(productId).get("reserved").asInt()).isEqualTo(10);
        assertThat(api.product(productId).get("available").asInt()).isZero();
    }

    @Test
    @DisplayName("R10.2 totalQuantity 5 쿠폰을 서로 다른 사용자 15명이 동시 사용 → 201 5건, 409 10건, usedCount 5")
    void couponQuantity() throws Exception {
        long productId = api.createProduct(1_000, 1_000);
        Map<String, Object> request = coupon(newCouponCode(), "FIXED", 100);
        request.put("totalQuantity", 5);
        String code = api.createCoupon(request);

        List<Response> responses = runConcurrently(IntStream.range(0, 15)
                .<Callable<Response>>mapToObj(i -> () -> api.createOrder(newUserId(), code, List.of(item(productId, 1))))
                .toList());

        assertThat(count(responses, 201)).isEqualTo(5);
        assertThat(count(responses, 409)).isEqualTo(10);
        responses.stream().filter(r -> r.status() == 409).forEach(r -> r.assertProblem(409, "COUPON_EXHAUSTED"));
        assertThat(api.coupon(code).get("usedCount").asInt()).isEqualTo(5);
        assertThat(api.product(productId).get("reserved").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 주문 5건 동시(키 다름) → 201 정확히 1건")
    void couponPerUser() throws Exception {
        long productId = api.createProduct(1_000, 1_000);
        String code = api.createCoupon("FIXED", 100);
        String user = newUserId();

        List<Response> responses = runConcurrently(IntStream.range(0, 5)
                .<Callable<Response>>mapToObj(i -> () -> api.createOrder(user, code, List.of(item(productId, 1))))
                .toList());

        assertThat(count(responses, 201)).isEqualTo(1);
        responses.stream().filter(r -> r.status() != 201).forEach(r -> r.assertProblem(409, "COUPON_NOT_APPLICABLE"));
        assertThat(api.coupon(code).get("usedCount").asInt()).isEqualTo(1);
        assertThat(api.product(productId).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R10.4 [P,Q]·[Q,P] 순서 주문을 섞어 동시 요청 → 5xx 없이 모두 처리, reserved 정확")
    void crossOrderNoDeadlock() throws Exception {
        long p = api.createProduct(1_000, 1_000);
        long q = api.createProduct(1_000, 1_000);

        List<Response> responses = runConcurrently(IntStream.range(0, 30)
                .<Callable<Response>>mapToObj(i -> () -> api.createOrder(newUserId(), null, i % 2 == 0
                        ? List.of(item(p, 1), item(q, 2))
                        : List.of(item(q, 2), item(p, 1))))
                .toList());

        assertThat(responses).allSatisfy(r -> r.assertStatus(201));
        assertThat(api.product(p).get("reserved").asInt()).isEqualTo(30);
        assertThat(api.product(q).get("reserved").asInt()).isEqualTo(60);
    }

    @Test
    @DisplayName("R10.4 재고 경합 상황에서도 교착 없이 reserved가 정확")
    void crossOrderUnderContention() throws Exception {
        long p = api.createProduct(1_000, 10);
        long q = api.createProduct(1_000, 10);

        List<Response> responses = runConcurrently(IntStream.range(0, 20)
                .<Callable<Response>>mapToObj(i -> () -> api.createOrder(newUserId(), null, i % 2 == 0
                        ? List.of(item(p, 1), item(q, 1))
                        : List.of(item(q, 1), item(p, 1))))
                .toList());

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
        assertThat(count(responses, 201)).isEqualTo(10);
        assertThat(api.product(p).get("reserved").asInt()).isEqualTo(10);
        assertThat(api.product(q).get("reserved").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R10.5 같은 주문에 결제 동시 요청(키 다름) → PG 결제 요청 최대 1번, 성공 1건")
    void concurrentPayments() throws Exception {
        long productId = api.createProduct(1_000, 10);
        long orderId = api.createOrderId(newUserId(), productId, 2);
        PG.paymentDelayMillis(300);

        List<Response> responses = runConcurrently(IntStream.range(0, 8)
                .<Callable<Response>>mapToObj(i -> () -> api.pay(orderId, newKey(), "tok"))
                .toList());

        assertThat(PG.paymentCalls()).hasSizeLessThanOrEqualTo(1);
        assertThat(count(responses, 200)).isEqualTo(1);
        responses.stream().filter(r -> r.status() != 200).forEach(r -> r.assertProblem(409, "INVALID_STATE"));
        assertThat(api.order(orderId).get("status").asText()).isEqualTo("PAID");
        assertThat(api.product(productId).get("stock").asInt()).isEqualTo(8);
        assertThat(api.product(productId).get("reserved").asInt()).isZero();
    }

    private static long count(List<Response> responses, int status) {
        return responses.stream().filter(r -> r.status() == status).count();
    }
}
