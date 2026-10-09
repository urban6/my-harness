package com.example.order;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.lessThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R10 동시성")
class R10ConcurrencyTest extends AbstractIntegrationTest {

    private static void assertNo5xx(List<ApiResponse> rs) {
        assertThat(rs).noneMatch(r -> r.status() >= 500);
    }

    @Test
    @DisplayName("R10.1 available 10 상품에 수량 1 주문 20건 동시 -> 정확히 10건 201, 10건 409 INSUFFICIENT_STOCK, reserved 10")
    void stock_20requests_on_10available() {
        long p = newProduct(1_000, 10);
        List<Callable<ApiResponse>> tasks = IntStream.range(0, 20)
                .<Callable<ApiResponse>>mapToObj(i -> () -> createOrder(uniqueUser(), uniqueKey(), null, p, 1))
                .toList();

        List<ApiResponse> rs = runConcurrently(tasks);

        assertNo5xx(rs);
        assertThat(countStatus(rs, 201)).isEqualTo(10);
        assertThat(countCode(rs, 409, "INSUFFICIENT_STOCK")).isEqualTo(10);
        ApiResponse product = getProduct(p);
        assertThat(product.json("reserved").asInt()).isEqualTo(10);
        assertThat(product.json("stock").asInt()).isEqualTo(10);
        assertThat(product.json("available").asInt()).isZero();
    }

    @Test
    @DisplayName("R10.1 수량이 서로 다른 동시 주문에서도 reserved 는 stock 을 넘지 않고 성공한 주문 수량의 합과 같다")
    void stock_variableQuantities_neverOversold() {
        long p = newProduct(1_000, 25);
        List<Callable<ApiResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int qty = 1 + (i % 4); // 1..4
            tasks.add(() -> createOrder(uniqueUser(), uniqueKey(), null, p, qty));
        }

        List<ApiResponse> rs = runConcurrently(tasks);

        assertNo5xx(rs);
        int sum = rs.stream().filter(r -> r.status() == 201)
                .mapToInt(r -> r.json("items").get(0).get("quantity").asInt()).sum();
        assertThat(sum).isLessThanOrEqualTo(25);
        assertThat(reservedOf(p)).isEqualTo(sum);
        assertThat(rs).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
    }

    @Test
    @DisplayName("R10.2 totalQuantity 5 쿠폰을 15명이 동시 사용 -> 정확히 5건 201, 10건 409 COUPON_EXHAUSTED, usedCount 5")
    void coupon_15users_on_5quantity() {
        String coupon = newCoupon("FIXED", 100, null, null, 5);
        long p = newProduct(1_000, 100);
        List<Callable<ApiResponse>> tasks = IntStream.range(0, 15)
                .<Callable<ApiResponse>>mapToObj(i -> () -> createOrder(uniqueUser(), uniqueKey(), coupon, p, 1))
                .toList();

        List<ApiResponse> rs = runConcurrently(tasks);

        assertNo5xx(rs);
        assertThat(countStatus(rs, 201)).isEqualTo(5);
        assertThat(countCode(rs, 409, "COUPON_EXHAUSTED")).isEqualTo(10);
        assertThat(usedCountOf(coupon)).isEqualTo(5);
        assertThat(reservedOf(p)).isEqualTo(5);
    }

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 주문 5건 동시 요청(키 다름) -> 정확히 1건 201, usedCount 1")
    void coupon_sameUser_5concurrent_onlyOne() {
        String coupon = newCoupon("FIXED", 100, null, null, 10);
        long p = newProduct(1_000, 100);
        String user = uniqueUser();
        List<Callable<ApiResponse>> tasks = IntStream.range(0, 5)
                .<Callable<ApiResponse>>mapToObj(i -> () -> createOrder(user, uniqueKey(), coupon, p, 1)).toList();

        List<ApiResponse> rs = runConcurrently(tasks);

        assertNo5xx(rs);
        assertThat(countStatus(rs, 201)).isEqualTo(1);
        assertThat(countCode(rs, 409, "COUPON_NOT_APPLICABLE")).isEqualTo(4);
        assertThat(usedCountOf(coupon)).isEqualTo(1);
        assertThat(reservedOf(p)).isEqualTo(1);
        assertThat(listOrders("userId=" + user).json("content")).hasSize(1);
    }

    @Test
    @DisplayName("R10.4 [P,Q] 와 [Q,P] 순서 주문을 섞어 동시 요청 -> 5xx 없이 모두 201, reserved 정확")
    void crossOrderedItems_noDeadlock() {
        long pId = newProduct(1_000, 1_000);
        long qId = newProduct(1_000, 1_000);
        List<Callable<ApiResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> createOrder(uniqueUser(), uniqueKey(), null, pId, 1, qId, 2)); // [P, Q]
            tasks.add(() -> createOrder(uniqueUser(), uniqueKey(), null, qId, 1, pId, 2)); // [Q, P]
        }

        List<ApiResponse> rs = runConcurrently(tasks);

        assertNo5xx(rs);
        assertThat(countStatus(rs, 201)).isEqualTo(40);
        assertThat(reservedOf(pId)).isEqualTo(20 * 1 + 20 * 2);
        assertThat(reservedOf(qId)).isEqualTo(20 * 2 + 20 * 1);
    }

    @Test
    @DisplayName("R10.4 교차 순서 + 재고 부족이 섞여도 5xx 없이 201/409 만, 성공 주문 수량만큼만 reserved")
    void crossOrderedItems_withContention_noDeadlock() {
        long pId = newProduct(1_000, 10);
        long qId = newProduct(1_000, 10);
        List<Callable<ApiResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> createOrder(uniqueUser(), uniqueKey(), null, pId, 1, qId, 1));
            tasks.add(() -> createOrder(uniqueUser(), uniqueKey(), null, qId, 1, pId, 1));
        }

        List<ApiResponse> rs = runConcurrently(tasks);

        assertNo5xx(rs);
        assertThat(rs).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
        assertThat(countStatus(rs, 201)).isEqualTo(10);
        assertThat(countCode(rs, 409, "INSUFFICIENT_STOCK")).isEqualTo(10);
        assertThat(reservedOf(pId)).isEqualTo(10);
        assertThat(reservedOf(qId)).isEqualTo(10);
    }

    @Test
    @DisplayName("R10.5 같은 주문에 결제 8건 동시 요청(키 다름) -> PG 결제 요청 1번, 성공 응답 1건")
    void samePaymentConcurrent_pgCalledOnce_oneSuccess() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 2);
        stubPgPaymentDelayed("APPROVED", "pay-race", 400);
        List<String> keys = IntStream.range(0, 8).mapToObj(i -> uniqueKey()).toList();
        List<Callable<ApiResponse>> tasks = keys.stream()
                .<Callable<ApiResponse>>map(k -> () -> pay(order.id(), k, "tok")).toList();

        List<ApiResponse> rs = runConcurrently(tasks);

        assertNo5xx(rs);
        assertThat(countStatus(rs, 200)).isEqualTo(1);
        assertThat(countCode(rs, 409, "INVALID_STATE")).isEqualTo(7);
        WIREMOCK.verify(lessThanOrExactly(1), postRequestedFor(urlEqualTo("/v1/payments")));
        WIREMOCK.verify(exactly(1), postRequestedFor(urlEqualTo("/v1/payments")));
        // PG 로 간 요청의 Idempotency-Key 는 클라이언트 키 중 하나
        String sentKey = WIREMOCK.findAll(postRequestedFor(urlEqualTo("/v1/payments"))).get(0)
                .getHeader("Idempotency-Key");
        assertThat(keys).contains(sentKey);
        assertThat(statusOf(order.id())).isEqualTo("PAID");
        assertThat(stockOf(p)).isEqualTo(8);
        assertThat(reservedOf(p)).isZero();
    }

    @Test
    @DisplayName("R10.5 동시 결제 중 PG 가 거절하면 PG 1번, 402 1건, 나머지 409, 복원은 1번만")
    void samePaymentConcurrent_declined_restoredOnce() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse order = newOrder(uniqueUser(), coupon, p, 3);
        stubPgPaymentDelayed("DECLINED", "pay-race-no", 300);
        List<Callable<ApiResponse>> tasks = IntStream.range(0, 6)
                .<Callable<ApiResponse>>mapToObj(i -> () -> pay(order.id(), uniqueKey(), "tok")).toList();

        List<ApiResponse> rs = runConcurrently(tasks);

        assertNo5xx(rs);
        assertThat(countCode(rs, 402, "PAYMENT_DECLINED")).isEqualTo(1);
        assertThat(countCode(rs, 409, "INVALID_STATE")).isEqualTo(5);
        WIREMOCK.verify(exactly(1), postRequestedFor(urlEqualTo("/v1/payments")));
        assertThat(reservedOf(p)).isZero();
        assertThat(usedCountOf(coupon)).isZero();
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    @DisplayName("R10 결제와 취소가 동시에 와도 한쪽만 이긴다 (재고·예약 정합 유지)")
    void payAndCancelRace_consistent() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 2);
        stubPgPaymentDelayed("APPROVED", "pay-vs-cancel", 300);
        stubPgRefund("pay-vs-cancel");
        List<Callable<ApiResponse>> tasks = List.of(() -> pay(order.id(), uniqueKey(), "tok"),
                () -> cancel(order.id()));

        List<ApiResponse> rs = runConcurrently(tasks);

        assertNo5xx(rs);
        String status = statusOf(order.id());
        assertThat(status).isIn("PAID", "CANCELLED", "REFUNDED");
        assertThat(reservedOf(p)).isZero();
        assertThat(stockOf(p)).isEqualTo("PAID".equals(status) ? 8 : 10);
    }
}
