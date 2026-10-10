package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderConcurrencySmokeTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R10.1 available 10에 20건 동시 주문 -> 10건 201, 10건 409, reserved 10")
    void stock_20concurrentOrders_exactly10succeed() {
        long productId = newProduct(1000, 10);

        List<ApiResponse> results = runConcurrently(20, i -> placeOrder(null, line(productId, 1)));

        assertThat(countStatus(results, 201)).isEqualTo(10);
        assertThat(countStatus(results, 409)).isEqualTo(10);
        assertThat(getProduct(productId).json().get("reserved").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R10.2 쿠폰 5개에 15명 동시 사용 -> 5건 201, 10건 409, usedCount 5")
    void coupon_15usersFor5Quantity_exactly5succeed() {
        long productId = newProduct(1000, 100);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);

        List<ApiResponse> results = runConcurrently(15, i -> placeOrder(coupon, line(productId, 1)));

        assertThat(countStatus(results, 201)).isEqualTo(5);
        assertThat(countStatus(results, 409)).isEqualTo(10);
        assertThat(results.stream().filter(r -> r.status() == 409).map(ApiResponse::code))
                .allMatch("COUPON_EXHAUSTED"::equals);
        assertThat(getCoupon(coupon).longValue("usedCount")).isEqualTo(5);
        assertThat(getProduct(productId).json().get("reserved").asInt()).isEqualTo(5); // 실패한 주문은 예약을 남기지 않는다
    }

    @Test
    @DisplayName("R10.3 한 사용자의 동일 쿠폰 5건 동시 주문 -> 정확히 1건 201")
    void coupon_sameUserFiveConcurrentOrders_exactlyOneSucceeds() {
        long productId = newProduct(1000, 100);
        String coupon = newCoupon("FIXED", 100, 0, null, 50);
        String user = uniqueUser();

        List<ApiResponse> results = runConcurrently(5,
                i -> postOrder(user, uniqueKey(), orderJson(coupon, line(productId, 1))));

        assertThat(countStatus(results, 201)).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(4);
        assertThat(getCoupon(coupon).longValue("usedCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("R10.4 [P,Q]/[Q,P] 혼합 동시 주문은 5xx 없이 reserved 정확")
    void lockOrder_mixedProductOrder_noDeadlock_andReservedIsExact() {
        long p = newProduct(1000, 1000);
        long q = newProduct(1000, 1000);

        List<ApiResponse> results = runConcurrently(20, i -> i % 2 == 0
                ? placeOrder(null, line(p, 1), line(q, 1))
                : placeOrder(null, line(q, 1), line(p, 1)));

        assertThat(results).allMatch(r -> r.status() == 201, "all created without 5xx");
        assertThat(getProduct(p).json().get("reserved").asInt()).isEqualTo(20);
        assertThat(getProduct(q).json().get("reserved").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("R10.5 같은 주문 동시 결제는 PG 1회, 성공 1건")
    void pay_concurrentOnSameOrder_callsGatewayOnce_andOneSuccess() {
        long productId = newProduct(1000, 10);
        long orderId = placeOrderOk(productId, 1).id();

        List<ApiResponse> results = runConcurrently(8, i -> pay(orderId));

        assertThat(countStatus(results, 200)).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(7);
        assertThat(PG.paymentCallsForOrder(orderId)).hasSize(1);
        assertThat(getProduct(productId).json().get("stock").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("R4.5 같은 키 동시 요청은 한 번만 처리")
    void idempotency_sameKeyConcurrent_processedOnce() {
        long productId = newProduct(1000, 100);
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderJson(null, line(productId, 1));

        List<ApiResponse> results = runConcurrently(10, i -> postOrder(user, key, body));

        assertThat(results).allMatch(r -> r.status() == 201 || r.status() == 409);
        assertThat(countStatus(results, 201)).isGreaterThanOrEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() == 201).map(ApiResponse::body).distinct()).hasSize(1);
        assertThat(getProduct(productId).json().get("reserved").asInt()).isEqualTo(1);
    }
}
