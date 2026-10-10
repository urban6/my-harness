package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R10. 동시성. 스펙 그대로의 수치(20건 중 10건 201 등)는 스모크
 * (OrderConcurrencySmokeTest: R10.1~R10.5)가 검증하고, 여기서는 그 변형과 경합 조합을 추가로 검증한다.
 */
class R10ConcurrencyTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R10.1 available 10, 수량 1 주문 20건 동시 요청 -> 정확히 10건 201, 10건 409 INSUFFICIENT_STOCK, reserved 10, available 0")
    void r10_1_exactNumbers_withErrorCodes() {
        long productId = newProduct(1_000, 10);

        List<ApiResponse> results = runConcurrently(20, i -> placeOrder(null, line(productId, 1)));

        assertThat(countStatus(results, 201)).isEqualTo(10);
        assertThat(countStatus(results, 409)).isEqualTo(10);
        assertThat(results.stream().filter(r -> r.status() == 409).map(ApiResponse::code))
                .allMatch("INSUFFICIENT_STOCK"::equals);
        assertThat(results.stream().filter(r -> r.status() == 201).map(ApiResponse::id).distinct()).hasSize(10);
        assertThat(reserved(productId)).isEqualTo(10);
        assertThat(available(productId)).isZero();
    }

    @Test
    @DisplayName("R10.1 수량 2 주문 20건이 stock 10에 동시에 오면 정확히 5건만 201이고 reserved는 10을 넘지 않는다")
    void r10_1_multiQuantity_neverOverReserves() {
        long productId = newProduct(1_000, 10);

        List<ApiResponse> results = runConcurrently(20, i -> placeOrder(null, line(productId, 2)));

        assertThat(countStatus(results, 201)).isEqualTo(5);
        assertThat(countStatus(results, 409)).isEqualTo(15);
        assertThat(reserved(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("R10.1 동시에 만든 주문 10건을 동시에 취소하면 reserved가 정확히 0으로 돌아오고 모두 200이다")
    void r10_1_concurrentCancel_restoresExactly() {
        long productId = newProduct(1_000, 10);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(placeOrderOk(productId, 1).id());
        }

        List<ApiResponse> results = runConcurrently(10, i -> cancel(ids.get(i)));

        assertThat(countStatus(results, 200)).isEqualTo(10);
        assertThat(reserved(productId)).isZero();
        assertThat(available(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("R10.2 totalQuantity 5 쿠폰을 사용자 15명이 동시에 쓰면 5건 201, 10건 409 COUPON_EXHAUSTED, usedCount 5, 상품 reserved도 5")
    void r10_2_exactNumbers_andReservationsOfRejectedOrdersAreNotKept() {
        long productId = newProduct(1_000, 100);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);

        List<ApiResponse> results = runConcurrently(15, i -> placeOrder(coupon, line(productId, 2)));

        assertThat(countStatus(results, 201)).isEqualTo(5);
        assertThat(countStatus(results, 409)).isEqualTo(10);
        assertThat(results.stream().filter(r -> r.status() == 409).map(ApiResponse::code))
                .allMatch("COUPON_EXHAUSTED"::equals);
        assertThat(usedCount(coupon)).isEqualTo(5);
        assertThat(reserved(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("R10.2 쿠폰과 재고가 함께 부족한 경합에서도 usedCount·reserved가 성공 건수와 정확히 일치한다")
    void r10_2_couponAndStockContention_countsMatchSuccesses() {
        long productId = newProduct(1_000, 3);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);

        List<ApiResponse> results = runConcurrently(15, i -> placeOrder(coupon, line(productId, 1)));

        long created = countStatus(results, 201);
        assertThat(created).isEqualTo(3);
        assertThat(results).allMatch(r -> r.status() == 201 || r.status() == 409);
        assertThat(usedCount(coupon)).isEqualTo(created);
        assertThat(reserved(productId)).isEqualTo((int) created);
    }

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 서로 다른 키의 주문 5건을 동시에 요청하면 정확히 1건 201, 나머지는 409 COUPON_NOT_APPLICABLE이다")
    void r10_3_sameUser_exactlyOne_withErrorCodes() {
        long productId = newProduct(1_000, 100);
        String coupon = newCoupon("FIXED", 100, 0, null, 50);
        String user = uniqueUser();

        List<ApiResponse> results = runConcurrently(5,
                i -> postOrder(user, uniqueKey(), orderJson(coupon, line(productId, 1))));

        assertThat(countStatus(results, 201)).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() == 409).map(ApiResponse::code))
                .hasSize(4).allMatch("COUPON_NOT_APPLICABLE"::equals);
        assertThat(usedCount(coupon)).isEqualTo(1);
        assertThat(reserved(productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("R10.4 [P,Q]와 [Q,P] 순서 주문 30건이 재고 15에 몰려도 5xx 없이 처리되고 reserved가 정확하다")
    void r10_4_mixedLockOrder_withScarcity_noServerErrors_andReservedExact() {
        long p = newProduct(1_000, 15);
        long q = newProduct(1_000, 15);

        List<ApiResponse> results = runConcurrently(30, i -> i % 2 == 0
                ? placeOrder(null, line(p, 1), line(q, 1))
                : placeOrder(null, line(q, 1), line(p, 1)));

        assertThat(results).noneMatch(r -> r.status() >= 500);
        assertThat(countStatus(results, 201)).isEqualTo(15);
        assertThat(countStatus(results, 409)).isEqualTo(15);
        assertThat(reserved(p)).isEqualTo(15);
        assertThat(reserved(q)).isEqualTo(15);
    }

    @Test
    @DisplayName("R10.4 상품 조합이 다른 주문 생성과 결제·취소가 섞여 동시에 와도 5xx 없이 끝나고 stock·reserved가 정확하다")
    void r10_4_mixedOperations_noServerErrors_countsExact() {
        long a = newProduct(1_000, 100);
        long b = newProduct(1_000, 100);
        long c = newProduct(1_000, 100);
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            orders.add(placeOrder(null, line(a, 1), line(b, 1), line(c, 1)).id());
        }

        // i%4==0: [a,c,b] 신규 6건, i%4==1: [c,b] 신규 6건, i%4==2: 주문 0·2·4 결제(각 2회 시도), i%4==3: 주문 1·3·5 취소(각 2회 시도)
        List<ApiResponse> results = runConcurrently(24, i -> switch (i % 4) {
            case 0 -> placeOrder(null, line(a, 1), line(c, 1), line(b, 1));
            case 1 -> placeOrder(null, line(c, 1), line(b, 1));
            case 2 -> pay(orders.get(i % 6));
            default -> cancel(orders.get(i % 6));
        });

        assertThat(results).noneMatch(r -> r.status() >= 500);
        assertThat(countStatus(results, 201)).isEqualTo(12);
        // 결제 3건 확정(주문 0·2·4), 취소 3건 확정(주문 1·3·5)
        assertThat(stock(a)).isEqualTo(97);
        assertThat(stock(b)).isEqualTo(97);
        assertThat(stock(c)).isEqualTo(97);
        assertThat(reserved(a)).isEqualTo(6);
        assertThat(reserved(b)).isEqualTo(12);
        assertThat(reserved(c)).isEqualTo(12);
        assertThat(PG.paymentCallCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("R10.5 같은 주문에 서로 다른 키로 결제 8건이 동시에 와도 PG 결제 요청은 1번, 200은 1건, 나머지는 409 INVALID_STATE이다 (PG 지연 300ms)")
    void r10_5_slowGateway_stillExactlyOnePgCall() {
        long productId = newProduct(1_000, 10);
        long orderId = placeOrderOk(productId, 2).id();
        PG.delayPayment(300);

        List<ApiResponse> results = runConcurrently(8, i -> pay(orderId));

        assertThat(countStatus(results, 200)).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() != 200).map(ApiResponse::code))
                .hasSize(7).allMatch("INVALID_STATE"::equals);
        assertThat(PG.paymentCallsForOrder(orderId)).hasSize(1);
        assertThat(stock(productId)).isEqualTo(8);
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R10.5 서로 다른 주문 10건의 결제가 동시에 와도 각각 한 번씩만 PG를 호출하고 stock이 정확히 10 줄고 reserved는 0이다")
    void r10_5_differentOrders_paidConcurrently_eachOnce() {
        long productId = newProduct(1_000, 10);
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            orders.add(placeOrderOk(productId, 1).id());
        }

        List<ApiResponse> results = runConcurrently(10, i -> pay(orders.get(i)));

        assertThat(countStatus(results, 200)).isEqualTo(10);
        assertThat(PG.paymentCallCount()).isEqualTo(10);
        for (long id : orders) {
            assertThat(PG.paymentCallsForOrder(id)).hasSize(1);
        }
        assertThat(stock(productId)).isZero();
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R10.5 같은 주문의 결제와 취소가 동시에 와도 재고가 보존된다 (최종 CANCELLED 또는 REFUNDED, stock 원복·reserved 0, PG 결제 ≤ 1)")
    void r10_5_payVersusCancel_stockConserved() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(productId, 3)).id();

        List<ApiResponse> results = runConcurrently(2, i -> i == 0 ? pay(orderId) : cancel(orderId));

        assertThat(results).noneMatch(r -> r.status() >= 500);
        String finalStatus = getOrder(orderId).text("status");
        assertThat(finalStatus).isIn("CANCELLED", "REFUNDED");
        assertThat(stock(productId)).isEqualTo(10);
        assertThat(reserved(productId)).isZero();
        assertThat(usedCount(coupon)).isZero();
        assertThat(PG.paymentCallsForOrder(orderId).size()).isLessThanOrEqualTo(1);
    }
}
