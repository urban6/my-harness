package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R10. 동시성 */
class ConcurrencyTest extends IntegrationTestBase {

    @Test
    @DisplayName("R10.1 available 10인 상품에 수량 1 주문 20건 동시: 201 10건, 409 10건, reserved 10")
    void stockContention() {
        long p = product(1000, 10);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String user = "user-" + i;
            tasks.add(() -> createOrder(user, "k-" + uniq(), items(p, 1), null));
        }

        List<Res> results = concurrently(tasks);

        assertThat(count(results, 201)).isEqualTo(10);
        assertThat(count(results, 409)).isEqualTo(10);
        results.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertThat(r.code()).isEqualTo("INSUFFICIENT_STOCK"));
        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(10);
        assertThat(productOf(p).get("available").asLong()).isZero();
    }

    @Test
    @DisplayName("R10.2 totalQuantity 5 쿠폰을 사용자 15명이 동시 사용: 201 5건, 409 10건, usedCount 5")
    void couponQuantityContention() {
        long p = product(1000, 1000);
        String coupon = coupon("FIXED", 100, 0, null, 5);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String user = "cu-" + uniq().substring(0, 8);
            tasks.add(() -> createOrder(user, "k-" + uniq(), items(p, 1), coupon));
        }

        List<Res> results = concurrently(tasks);

        assertThat(count(results, 201)).isEqualTo(5);
        assertThat(count(results, 409)).isEqualTo(10);
        results.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertThat(r.code()).isEqualTo("COUPON_EXHAUSTED"));
        assertThat(couponOf(coupon).get("usedCount").asLong()).isEqualTo(5);
        // 쿠폰 때문에 거절된 주문은 재고를 잡지 않는다.
        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 주문 5건 동시 요청(키 다름): 정확히 1건 201")
    void sameUserSameCoupon() {
        long p = product(1000, 100);
        String coupon = coupon("FIXED", 100, 0, null, 10);
        String user = "solo-" + uniq().substring(0, 8);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> createOrder(user, "k-" + uniq(), items(p, 1), coupon));
        }

        List<Res> results = concurrently(tasks);

        assertThat(count(results, 201)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(4);
        results.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertThat(r.code()).isEqualTo("COUPON_NOT_APPLICABLE"));
        assertThat(couponOf(coupon).get("usedCount").asLong()).isEqualTo(1);
        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("R10.4 [P,Q]와 [Q,P] 순서 주문을 섞어 동시에 요청해도 5xx 없이 처리되고 reserved가 정확하다")
    void lockOrderingNoDeadlock() {
        long p = product(1000, 1000);
        long q = product(1000, 1000);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            boolean forward = i % 2 == 0;
            String user = "dl-" + i;
            tasks.add(() -> createOrder(user, "k-" + uniq(),
                    forward ? items(p, 1, q, 2) : items(q, 2, p, 1), null));
        }

        List<Res> results = concurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(30);
        assertThat(productOf(q).get("reserved").asLong()).isEqualTo(60);
    }

    @Test
    @DisplayName("R10.4 재고가 모자란 혼합 순서 경합: 5xx 없이 201/409만 나오고 초과 예약이 없다")
    void lockOrderingWithShortage() {
        long p = product(1000, 10);
        long q = product(1000, 10);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            boolean forward = i % 2 == 0;
            String user = "dls-" + i;
            tasks.add(() -> createOrder(user, "k-" + uniq(), forward ? items(p, 1, q, 1) : items(q, 1, p, 1), null));
        }

        List<Res> results = concurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
        assertThat(count(results, 201)).isEqualTo(10);
        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(10);
        assertThat(productOf(q).get("reserved").asLong()).isEqualTo(10);
    }

    @Test
    @DisplayName("R10.5 같은 주문에 결제 요청이 동시에 와도(키 다름) PG 결제는 최대 1번, 성공 응답은 1건")
    void concurrentPayments() {
        long p = product(1000, 10);
        long id = order(p, 2).get("id").asLong();
        GATEWAY.delay(300);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> pay(id, "tok_ok", "pay-" + uniq()));
        }

        List<Res> results = concurrently(tasks);

        assertThat(count(results, 200)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(9);
        results.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertThat(r.code()).isEqualTo("INVALID_STATE"));
        assertThat(GATEWAY.charges()).hasSize(1);
        assertThat(orderOf(id).get("status").asText()).isEqualTo("PAID");
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(8);
        assertThat(productOf(p).get("reserved").asLong()).isZero();
    }

    @Test
    @DisplayName("결제와 취소가 동시에 와도 결과는 하나로 수렴하고 재고가 어긋나지 않는다")
    void payVersusCancel() {
        for (int round = 0; round < 5; round++) {
            long p = product(1000, 10);
            long id = order(p, 3).get("id").asLong();
            GATEWAY.reset();
            GATEWAY.delay(100);

            List<Res> results = concurrently(List.of(
                    () -> pay(id),
                    () -> post("/api/orders/" + id + "/cancel", null)));

            String status = orderOf(id).get("status").asText();
            assertThat(status).isIn("PAID", "CANCELLED");
            assertThat(results).allSatisfy(r -> assertThat(r.status()).isIn(200, 409));
            long stock = productOf(p).get("stock").asLong();
            long reserved = productOf(p).get("reserved").asLong();
            if (status.equals("PAID")) {
                assertThat(stock).isEqualTo(7);
            } else {
                assertThat(stock).isEqualTo(10);
            }
            assertThat(reserved).isZero();
        }
    }
}
