package com.example.order.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R10 동시성")
class ConcurrencyTest extends IntegrationTest {

    @Test
    @DisplayName("R10.1 available 10 에 수량 1 주문 20건 동시 → 201 10건, 409 10건, reserved 10")
    void stockReservation() throws Exception {
        long productId = createProduct(1_000, 10);
        List<Callable<ApiResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> placeOrder(uniqueUser(), null, List.of(item(productId, 1))));
        }

        List<ApiResponse> results = concurrently(tasks);

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(10);
        assertThat(results).filteredOn(r -> r.status() == 409)
                .hasSize(10)
                .allSatisfy(r -> assertProblem(r, 409, "INSUFFICIENT_STOCK"));
        assertProduct(productId, 10, 10);
    }

    @Test
    @DisplayName("R10.2 totalQuantity 5 쿠폰을 서로 다른 사용자 15명이 동시에 → 201 5건, 409 10건, usedCount 5")
    void couponQuantity() throws Exception {
        long productId = createProduct(1_000, 100);
        String code = createCoupon(Map.of("totalQuantity", 5));
        List<Callable<ApiResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            tasks.add(() -> placeOrder(uniqueUser(), code, List.of(item(productId, 1))));
        }

        List<ApiResponse> results = concurrently(tasks);

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(5);
        assertThat(results).filteredOn(r -> r.status() == 409)
                .hasSize(10)
                .allSatisfy(r -> assertProblem(r, 409, "COUPON_EXHAUSTED"));
        assertThat(usedCount(code)).isEqualTo(5);
        assertProduct(productId, 100, 5);
    }

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 주문 5건을 동시에(키는 다름) → 정확히 1건 201")
    void sameUserSameCoupon() throws Exception {
        long productId = createProduct(1_000, 100);
        String code = createCoupon(Map.of("totalQuantity", 100));
        String user = uniqueUser();
        List<Callable<ApiResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> placeOrder(user, code, List.of(item(productId, 1))));
        }

        List<ApiResponse> results = concurrently(tasks);

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 201)
                .hasSize(4)
                .allSatisfy(r -> assertProblem(r, 409, "COUPON_NOT_APPLICABLE"));
        assertThat(usedCount(code)).isEqualTo(1);
        assertProduct(productId, 100, 1);
    }

    @Test
    @DisplayName("R10.4 [P, Q]·[Q, P] 순서 주문을 섞어 동시에 → 5xx 없이 모두 처리, reserved 정확")
    void noDeadlockAcrossItemOrder() throws Exception {
        long p = createProduct(1_000, 1_000);
        long q = createProduct(1_000, 1_000);
        List<Callable<ApiResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            List<Map<String, Object>> items = i % 2 == 0
                    ? List.of(item(p, 1), item(q, 2))
                    : List.of(item(q, 2), item(p, 1));
            tasks.add(() -> placeOrder(uniqueUser(), null, items));
        }

        List<ApiResponse> results = concurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.toString()).isEqualTo(201));
        assertProduct(p, 1_000, 30);
        assertProduct(q, 1_000, 60);
    }

    @Test
    @DisplayName("R10.4 생성·취소·결제가 [P, Q]·[Q, P] 로 섞여도 5xx 없이 처리되고 재고가 정확")
    void noDeadlockAcrossMixedOperations() throws Exception {
        long p = createProduct(1_000, 1_000);
        long q = createProduct(1_000, 1_000);
        List<Long> toCancel = new ArrayList<>();
        List<Long> toPay = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            toCancel.add(createOrder(uniqueUser(), null, List.of(item(q, 1), item(p, 1))));
            toPay.add(createOrder(uniqueUser(), null, List.of(item(p, 1), item(q, 1))));
        }
        List<Callable<ApiResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long cancelId = toCancel.get(i);
            long payId = toPay.get(i);
            tasks.add(() -> action(cancelId, "cancel"));
            tasks.add(() -> pay(payId));
            List<Map<String, Object>> items = i % 2 == 0
                    ? List.of(item(p, 1), item(q, 1))
                    : List.of(item(q, 1), item(p, 1));
            tasks.add(() -> placeOrder(uniqueUser(), null, items));
        }

        List<ApiResponse> results = concurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.toString()).isBetween(200, 201));
        // 결제 10건으로 stock 990, 새 주문 10건이 예약 중
        assertProduct(p, 990, 10);
        assertProduct(q, 990, 10);
    }

    @Test
    @DisplayName("R10.5 같은 주문에 결제가 동시에(키는 다름) → PG 결제 요청 최대 1번, 성공 응답 1건")
    void concurrentPayments() throws Exception {
        long productId = createProduct(1_000, 10);
        long orderId = createOrder(uniqueUser(), null, List.of(item(productId, 2)));
        PG.latency(Duration.ofMillis(300));
        List<Callable<ApiResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> pay(orderId));
        }

        List<ApiResponse> results = concurrently(tasks);

        assertThat(PG.paymentCalls()).hasSizeLessThanOrEqualTo(1);
        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 200)
                .hasSize(4)
                .allSatisfy(r -> assertProblem(r, 409, "INVALID_STATE"));
        assertProduct(productId, 8, 0);
    }
}
