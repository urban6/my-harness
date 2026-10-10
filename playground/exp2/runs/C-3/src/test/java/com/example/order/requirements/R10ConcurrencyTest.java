package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R10. 동시성")
class R10ConcurrencyTest extends IntegrationTestBase {

    // ---------------------------------------------------------------- R10.1

    @Test
    @DisplayName("R10.1 available 10 인 상품에 수량 1 주문 20건을 동시에 요청하면 정확히 10건 201, 10건 409, reserved 10")
    void r10_1_stockContention_exactlyTenSucceed() {
        long productId = product(1_000, 10);

        List<ResponseEntity<String>> results = runConcurrently(20,
                i -> createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 1))));

        assertThat(countStatus(results, 201)).isEqualTo(10);
        assertThat(countStatus(results, 409)).isEqualTo(10);
        assertThat(results.stream().filter(r -> statusOf(r) == 409)).allSatisfy(r -> assertProblem(r, 409, "INSUFFICIENT_STOCK"));
        assertThat(reservedOf(productId)).isEqualTo(10);
        assertThat(availableOf(productId)).isZero();
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @RepeatedTest(3)
    @DisplayName("R10.1 반복해도 초과 예약(oversell)이 생기지 않는다 (수량 2 주문, available 9)")
    void r10_1_repeated_neverOversells() {
        long productId = product(1_000, 9);

        List<ResponseEntity<String>> results = runConcurrently(12,
                i -> createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 2))));

        assertThat(countStatus(results, 201)).isEqualTo(4);
        assertThat(countStatus(results, 409)).isEqualTo(8);
        assertThat(reservedOf(productId)).isEqualTo(8);
        assertThat(availableOf(productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("R10.1 동시 요청의 성공 주문 수와 실제 주문 행의 수가 reserved 와 일치한다")
    void r10_1_createdOrdersMatchReserved() {
        long productId = product(1_000, 10);
        String marker = uid("m");

        runConcurrently(20, i -> createOrder(marker + "-" + i, uid("k"), orderBody(null, item(productId, 1))));

        Integer rows = jdbc.queryForObject(
                "select count(*) from order_items oi join orders o on o.id = oi.order_id "
                        + "where oi.product_id = ? and o.status = 'PENDING_PAYMENT'", Integer.class, productId);
        assertThat(rows).isEqualTo(10);
        assertThat(reservedOf(productId)).isEqualTo(10);
    }

    // ---------------------------------------------------------------- R10.2

    @Test
    @DisplayName("R10.2 totalQuantity 5 쿠폰을 서로 다른 사용자 15명이 동시에 쓰면 정확히 5건 201, 10건 409, usedCount 5")
    void r10_2_couponExhaustion_exactlyFiveSucceed() {
        long productId = product(1_000, 100);
        String code = uniqueCode("CONC");
        createCoupon(code, "FIXED", 100, 0, null, 5);

        List<ResponseEntity<String>> results = runConcurrently(15,
                i -> createOrder(uid("user"), uid("k"), orderBody(code, item(productId, 1))));

        assertThat(countStatus(results, 201)).isEqualTo(5);
        assertThat(countStatus(results, 409)).isEqualTo(10);
        assertThat(results.stream().filter(r -> statusOf(r) == 409)).allSatisfy(r -> assertProblem(r, 409, "COUPON_EXHAUSTED"));
        assertThat(usedCountOf(code)).isEqualTo(5L);
    }

    @Test
    @DisplayName("R10.2 쿠폰 소진으로 실패한 주문의 재고 예약은 남지 않는다 (reserved = 성공 건수)")
    void r10_2_failedCouponOrders_leaveNoReservation() {
        long productId = product(1_000, 100);
        String code = uniqueCode("CONR");
        createCoupon(code, "FIXED", 100, 0, null, 5);

        runConcurrently(15, i -> createOrder(uid("user"), uid("k"), orderBody(code, item(productId, 1))));

        assertThat(reservedOf(productId)).isEqualTo(5);
    }

    // ---------------------------------------------------------------- R10.3

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 주문 5건을 동시에 요청하면 정확히 1건만 201")
    void r10_3_sameUserSameCoupon_exactlyOneSucceeds() {
        long productId = product(1_000, 50);
        String code = uniqueCode("ONEU");
        createCoupon(code, "FIXED", 100, 0, null, 100);
        String user = uid("u");

        List<ResponseEntity<String>> results = runConcurrently(5,
                i -> createOrder(user, uid("k"), orderBody(code, item(productId, 1))));

        assertThat(countStatus(results, 201)).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(4);
        assertThat(results.stream().filter(r -> statusOf(r) == 409)).allSatisfy(r -> assertProblem(r, 409, "COUPON_NOT_APPLICABLE"));
        assertThat(usedCountOf(code)).isEqualTo(1L);
        assertThat(reservedOf(productId)).isEqualTo(1);
    }

    @RepeatedTest(3)
    @DisplayName("R10.3 반복해도 같은 사용자의 동시 쿠폰 주문은 1건만 성공한다 (10건 동시)")
    void r10_3_repeated_sameUserSameCoupon() {
        long productId = product(1_000, 50);
        String code = uniqueCode("ONER");
        createCoupon(code, "FIXED", 100, 0, null, 100);
        String user = uid("u");

        List<ResponseEntity<String>> results = runConcurrently(10,
                i -> createOrder(user, uid("k"), orderBody(code, item(productId, 1))));

        assertThat(countStatus(results, 201)).isEqualTo(1);
        assertThat(results.stream().filter(r -> statusOf(r) >= 500)).isEmpty();
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    // ---------------------------------------------------------------- R10.4

    @Test
    @DisplayName("R10.4 [P,Q] 와 [Q,P] 순서 주문을 섞어 동시에 요청해도 5xx 없이 모두 처리되고 reserved 가 정확하다")
    void r10_4_oppositeItemOrders_noDeadlock() {
        long p = product(1_000, 100);
        long q = product(2_000, 100);

        List<ResponseEntity<String>> results = runConcurrently(40, i -> {
            Object body = i % 2 == 0 ? orderBody(null, item(p, 1), item(q, 1)) : orderBody(null, item(q, 1), item(p, 1));
            return createOrder(uid("u"), uid("k"), body);
        });

        assertThat(results.stream().filter(r -> statusOf(r) >= 500)).as("5xx(데드락 포함)").isEmpty();
        assertThat(countStatus(results, 201)).isEqualTo(40);
        assertThat(reservedOf(p)).isEqualTo(40);
        assertThat(reservedOf(q)).isEqualTo(40);
    }

    @Test
    @DisplayName("R10.4 재고가 모자란 상태에서 섞인 순서로 경합해도 5xx 없이 성공 건수만큼만 reserved 가 잡힌다")
    void r10_4_oppositeOrders_withScarceStock_staysConsistent() {
        long p = product(1_000, 10);
        long q = product(2_000, 10);

        List<ResponseEntity<String>> results = runConcurrently(30, i -> {
            Object body = i % 2 == 0 ? orderBody(null, item(p, 1), item(q, 1)) : orderBody(null, item(q, 1), item(p, 1));
            return createOrder(uid("u"), uid("k"), body);
        });

        assertThat(results.stream().filter(r -> statusOf(r) >= 500)).as("5xx(데드락 포함)").isEmpty();
        long created = countStatus(results, 201);
        assertThat(created).isEqualTo(10);
        assertThat(countStatus(results, 409)).isEqualTo(20);
        assertThat(reservedOf(p)).isEqualTo(10);
        assertThat(reservedOf(q)).isEqualTo(10);
    }

    @Test
    @DisplayName("R10.4 생성·취소·결제가 섞여 동시에 일어나도 5xx 없이 최종 수치가 일관된다")
    void r10_4_mixedCreateCancelPay_staysConsistent() {
        long p = product(1_000, 100);
        long q = product(2_000, 100);
        List<Long> toCancel = new java.util.ArrayList<>();
        List<Long> toPay = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            toCancel.add(orderIdOk(uid("u"), orderBody(null, item(p, 1), item(q, 1))));
            toPay.add(orderIdOk(uid("u"), orderBody(null, item(q, 1), item(p, 1))));
        }

        List<ResponseEntity<String>> results = runConcurrently(24, i -> {
            if (i % 3 == 0) {
                return cancel(toCancel.get(i / 3 % toCancel.size()));
            }
            if (i % 3 == 1) {
                return pay(toPay.get(i / 3 % toPay.size()), uid("pk"), "tok");
            }
            return createOrder(uid("u"), uid("k"), orderBody(null, item(i % 2 == 0 ? p : q, 1)));
        });

        assertThat(results.stream().filter(r -> statusOf(r) >= 500)).as("5xx(데드락 포함)").isEmpty();
        // 취소된 8건은 예약 해제, 결제된 8건은 stock/reserved 확정, 새 주문 8건은 reserved 로 남는다
        int paid = 0;
        for (long id : toPay) {
            if ("PAID".equals(orderStatus(id))) {
                paid++;
            }
        }
        int cancelled = 0;
        for (long id : toCancel) {
            if ("CANCELLED".equals(orderStatus(id))) {
                cancelled++;
            }
        }
        Integer pendingP = jdbc.queryForObject("select coalesce(sum(oi.quantity),0) from order_items oi join orders o on o.id = oi.order_id "
                + "where oi.product_id = ? and o.status = 'PENDING_PAYMENT'", Integer.class, p);
        Integer pendingQ = jdbc.queryForObject("select coalesce(sum(oi.quantity),0) from order_items oi join orders o on o.id = oi.order_id "
                + "where oi.product_id = ? and o.status = 'PENDING_PAYMENT'", Integer.class, q);
        assertThat(reservedOf(p)).isEqualTo(pendingP);
        assertThat(reservedOf(q)).isEqualTo(pendingQ);
        assertThat(stockOf(p)).isEqualTo(100 - paid);
        assertThat(stockOf(q)).isEqualTo(100 - paid);
        assertThat(cancelled).isGreaterThan(0);
    }

    // ---------------------------------------------------------------- R10.5

    @Test
    @DisplayName("R10.5 같은 주문에 결제 요청이 동시에 와도(키는 다름) PG 결제 요청은 1번이고 성공 응답은 1건")
    void r10_5_concurrentPay_callsPgOnce_andOneSuccess() {
        long productId = product(10_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 2)));

        List<ResponseEntity<String>> results = runConcurrently(10, i -> pay(orderId, uid("pk"), "tok"));

        assertThat(PG.paymentCalls()).isEqualTo(1);
        assertThat(countStatus(results, 200)).isEqualTo(1);
        assertThat(results.stream().filter(r -> statusOf(r) != 200)).allSatisfy(r -> assertProblem(r, 409, "INVALID_STATE"));
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R10.5 PG 응답이 느려 경합 창이 넓어도 PG 호출은 1번, 성공은 1건이고 재고는 한 번만 차감된다")
    void r10_5_slowPg_stillCallsPgOnce() {
        long productId = product(10_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 2)));
        PG.delayPayments(700); // 지연 후 승인 (타임아웃 2초 이내)

        List<ResponseEntity<String>> results = runConcurrently(6, i -> pay(orderId, uid("pk"), "tok"));

        assertThat(PG.paymentCalls()).isEqualTo(1);
        assertThat(countStatus(results, 200)).isEqualTo(1);
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("R10.5 동시 결제 중 PG 가 거절하면 PG 호출 1번, 402 는 1건이고 예약은 한 번만 복원된다")
    void r10_5_concurrentPay_withDecline_restoresOnce() {
        long productId = product(10_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 2)));
        PG.declinePayments();

        List<ResponseEntity<String>> results = runConcurrently(6, i -> pay(orderId, uid("pk"), "tok"));

        assertThat(PG.paymentCalls()).isEqualTo(1);
        assertThat(countStatus(results, 402)).isEqualTo(1);
        assertThat(results.stream().filter(r -> statusOf(r) != 402)).allSatisfy(r -> assertProblem(r, 409, "INVALID_STATE"));
        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_FAILED");
        assertThat(reservedOf(productId)).isZero();
        assertThat(stockOf(productId)).isEqualTo(5);
    }

    @Test
    @DisplayName("R10.5 동시 취소(환불)도 PG 환불은 1번만 호출된다")
    void r10_5_concurrentCancelOfPaidOrder_refundsOnce() {
        long productId = product(10_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 2)));
        payOk(orderId);
        PG.delayRefunds(500);

        List<ResponseEntity<String>> results = runConcurrently(6, i -> cancel(orderId));

        assertThat(PG.refundCalls()).isEqualTo(1);
        assertThat(countStatus(results, 200)).isEqualTo(1);
        assertThat(stockOf(productId)).isEqualTo(5);
        JsonNode p = json(getProduct(productId));
        assertThat(p.get("reserved").asInt()).isZero();
    }

    @RepeatedTest(3)
    @DisplayName("R10.5 같은 주문에 결제와 취소가 동시에 와도 최종 상태와 재고 수치가 서로 일치한다")
    void r10_5_concurrentPayAndCancel_endsConsistent() {
        long productId = product(10_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 2)));

        List<ResponseEntity<String>> results = runConcurrently(2, i -> i == 0 ? pay(orderId, uid("pk"), "tok") : cancel(orderId));

        assertThat(results.stream().filter(r -> statusOf(r) >= 500)).isEmpty();
        String status = orderStatus(orderId);
        assertThat(status).isIn("PAID", "CANCELLED");
        if ("PAID".equals(status)) {
            assertThat(stockOf(productId)).isEqualTo(3);
        } else {
            assertThat(stockOf(productId)).isEqualTo(5);
        }
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("R10.5 DB 커넥션 풀(20)보다 많은 30건의 동시 결제가 와도 교착 없이 PG 호출 1번, 성공 1건으로 끝난다")
    void r10_5_paymentsAbovePoolSize_doNotStarveOrDeadlock() {
        long productId = product(10_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));
        PG.delayPayments(400);

        List<ResponseEntity<String>> results = runConcurrently(30, i -> pay(orderId, uid("pk"), "tok"));

        assertThat(results.stream().filter(r -> statusOf(r) >= 500)).as("5xx(풀 고갈 포함)").isEmpty();
        assertThat(PG.paymentCalls()).isEqualTo(1);
        assertThat(countStatus(results, 200)).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(29);
    }

    @Test
    @DisplayName("R10.1 DB 커넥션 풀(20)보다 많은 60건의 동시 주문 생성도 5xx 없이 처리되고 수치가 정확하다")
    void r10_1_createsAbovePoolSize_doNotStarve() {
        long productId = product(1_000, 25);

        List<ResponseEntity<String>> results = runConcurrently(60,
                i -> createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 1))));

        assertThat(results.stream().filter(r -> statusOf(r) >= 500)).as("5xx(풀 고갈 포함)").isEmpty();
        assertThat(countStatus(results, 201)).isEqualTo(25);
        assertThat(countStatus(results, 409)).isEqualTo(35);
        assertThat(reservedOf(productId)).isEqualTo(25);
    }
}
