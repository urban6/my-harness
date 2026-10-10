package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

/** R10. 동시성 */
class ConcurrencyTest extends IntegrationTestSupport {

    @Test
    void stock_twentyConcurrentOrdersForTenAvailable() throws Exception {
        long p = createProduct(1_000, 10);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> createOrder(uniqueUser(), null, item(p, 1)));
        }

        List<Res> results = concurrently(tasks);

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(10);
        assertThat(results).filteredOn(r -> r.status() == 409).hasSize(10)
                .allSatisfy(r -> assertProblem(r, 409, "INSUFFICIENT_STOCK"));
        assertThat(product(p).get("reserved").asInt()).isEqualTo(10);
        assertThat(product(p).get("available").asInt()).isZero();
    }

    @Test
    void coupon_fifteenUsersForFiveCoupons() throws Exception {
        long p = createProduct(1_000, 100);
        String code = createCoupon(Map.of("totalQuantity", 5));
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            tasks.add(() -> createOrder(uniqueUser(), code, item(p, 1)));
        }

        List<Res> results = concurrently(tasks);

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(5);
        assertThat(results).filteredOn(r -> r.status() == 409).hasSize(10)
                .allSatisfy(r -> assertProblem(r, 409, "COUPON_EXHAUSTED"));
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(5);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(5);
    }

    @Test
    void coupon_sameUserFiveConcurrentOrders_onlyOneSucceeds() throws Exception {
        long p = createProduct(1_000, 100);
        String code = createCoupon(Map.of("totalQuantity", 100));
        String user = uniqueUser();
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> createOrder(user, code, item(p, 1)));
        }

        List<Res> results = concurrently(tasks);

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 201).hasSize(4)
                .allSatisfy(r -> assertProblem(r, 409, "COUPON_NOT_APPLICABLE"));
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    void crossOrderedItems_doNotDeadlock() throws Exception {
        long p = createProduct(1_000, 1_000);
        long q = createProduct(1_000, 1_000);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(i % 2 == 0
                    ? () -> createOrder(uniqueUser(), null, item(p, 1), item(q, 2))
                    : () -> createOrder(uniqueUser(), null, item(q, 2), item(p, 1)));
        }

        List<Res> results = concurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).as("body=%s", r.body()).isEqualTo(201));
        assertThat(product(p).get("reserved").asInt()).isEqualTo(20);
        assertThat(product(q).get("reserved").asInt()).isEqualTo(40);
    }

    @Test
    void concurrentPaymentsForSameOrder_callGatewayAtMostOnce() throws Exception {
        long p = createProduct(1_000, 10);
        long orderId = placeOrder(uniqueUser(), null, item(p, 2));
        PG.paymentDelayMillis(300);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> pay(orderId, uniqueKey()));
        }

        List<Res> results = concurrently(tasks);

        assertThat(PG.payments()).hasSizeLessThanOrEqualTo(1);
        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 200)
                .allSatisfy(r -> assertProblem(r, 409, "INVALID_STATE"));
        assertThat(order(orderId).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asInt()).isEqualTo(8);
        assertThat(product(p).get("reserved").asInt()).isZero();
    }
}
