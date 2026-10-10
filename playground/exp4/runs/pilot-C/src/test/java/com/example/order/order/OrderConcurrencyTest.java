package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Response;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * R10. 동시성. 모든 스레드가 준비된 뒤 래치로 동시에 출발시키며(고정 sleep 없음),
 * 매 반복마다 새 상품·쿠폰·사용자로 격리한다.
 */
class OrderConcurrencyTest extends IntegrationTestBase {

    private static void assertNoServerErrors(List<ResponseEntity<JsonNode>> results) {
        results.forEach(r -> assertThat(r.getStatusCode().is5xxServerError())
                .as("unexpected 5xx: %s", r.getBody()).isFalse());
    }

    @RepeatedTest(value = 3, name = "R10.1 available 10 상품에 수량 1 주문 20건 동시 -> 201 정확히 10, 409 10, reserved 10 ({currentRepetition}/{totalRepetitions})")
    void r10_1_stockIsNeverOversold() {
        long productId = newProduct(1000, 10);

        List<ResponseEntity<JsonNode>> results = concurrently(20,
                i -> placeOrder(uniqueUser(), uniqueKey(), null, items(productId, 1)));

        assertNoServerErrors(results);
        assertThat(count(results, 201)).isEqualTo(10);
        assertThat(count(results, 409)).isEqualTo(10);
        results.stream().filter(r -> code(r) == 409)
                .forEach(r -> assertProblem(r, 409, "INSUFFICIENT_STOCK"));
        assertStock(productId, 10, 10);
        assertThat(product(productId).get("available").asInt()).isZero();
    }

    @RepeatedTest(value = 3, name = "R10.2 totalQuantity 5 쿠폰을 서로 다른 사용자 15명이 동시 사용 -> 201 정확히 5, 409 10, usedCount 5 ({currentRepetition}/{totalRepetitions})")
    void r10_2_couponQuantityIsNeverExceeded() {
        long productId = newProduct(1000, 100);
        String code = newCoupon("FIXED", 100, 5);

        List<ResponseEntity<JsonNode>> results = concurrently(15,
                i -> placeOrder(uniqueUser(), uniqueKey(), code, items(productId, 1)));

        assertNoServerErrors(results);
        assertThat(count(results, 201)).isEqualTo(5);
        assertThat(count(results, 409)).isEqualTo(10);
        results.stream().filter(r -> code(r) == 409)
                .forEach(r -> assertProblem(r, 409, "COUPON_EXHAUSTED"));
        assertUsedCount(code, 5);
        assertStock(productId, 100, 5); // 쿠폰 때문에 실패한 주문은 재고를 잡고 있지 않다
    }

    @RepeatedTest(value = 3, name = "R10.3 한 사용자가 같은 쿠폰으로 주문 5건 동시(키 서로 다름) -> 201 정확히 1건 ({currentRepetition}/{totalRepetitions})")
    void r10_3_sameUserCanUseCouponOnlyOnce() {
        long productId = newProduct(1000, 100);
        String code = newCoupon("FIXED", 100, 100);
        String user = uniqueUser();

        List<ResponseEntity<JsonNode>> results = concurrently(5,
                i -> placeOrder(user, uniqueKey(), code, items(productId, 1)));

        assertNoServerErrors(results);
        assertThat(count(results, 201)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(4);
        results.stream().filter(r -> code(r) == 409)
                .forEach(r -> assertProblem(r, 409, "COUPON_NOT_APPLICABLE"));
        assertUsedCount(code, 1);
        assertStock(productId, 100, 1);
        assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ? and coupon_code = ?",
                Integer.class, user, code)).isEqualTo(1);
    }

    @RepeatedTest(value = 3, name = "R10.4 [P,Q] / [Q,P] 순서 주문을 섞어 동시 요청 -> 5xx 없이 모두 처리, reserved 정확 ({currentRepetition}/{totalRepetitions})")
    void r10_4_oppositeLockOrderDoesNotDeadlock() {
        long p = newProduct(1000, 100);
        long q = newProduct(1000, 100);

        List<ResponseEntity<JsonNode>> results = concurrently(20, i -> i % 2 == 0
                ? placeOrder(uniqueUser(), uniqueKey(), null, items(p, 1, q, 2))
                : placeOrder(uniqueUser(), uniqueKey(), null, items(q, 2, p, 1)));

        assertNoServerErrors(results);
        assertThat(count(results, 201)).isEqualTo(20);
        assertStock(p, 100, 20);
        assertStock(q, 100, 40);
    }

    @Test
    @DisplayName("R10.4 서로 반대 순서 주문 중 일부가 재고 부족이어도 5xx 없이 정확히 처리된다 (P 재고 8, Q 재고 100)")
    void r10_4_oppositeLockOrderWithPartialStockShortage() {
        long p = newProduct(1000, 8);
        long q = newProduct(1000, 100);

        List<ResponseEntity<JsonNode>> results = concurrently(20, i -> i % 2 == 0
                ? placeOrder(uniqueUser(), uniqueKey(), null, items(p, 1, q, 1))
                : placeOrder(uniqueUser(), uniqueKey(), null, items(q, 1, p, 1)));

        assertNoServerErrors(results);
        assertThat(count(results, 201)).isEqualTo(8);
        assertThat(count(results, 409)).isEqualTo(12);
        assertStock(p, 8, 8);
        assertStock(q, 100, 8); // 실패한 주문의 Q 예약은 롤백된다
    }

    @RepeatedTest(value = 3, name = "R10.5 같은 주문에 결제 동시 요청(키 서로 다름) -> PG 결제 요청 1번, 성공 200 정확히 1건 ({currentRepetition}/{totalRepetitions})")
    void r10_5_concurrentPaymentsReachGatewayOnce() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 3).get("id").asLong();
        PG.respondWith(r -> r.path().equals("/v1/payments")
                ? Response.json("{\"paymentId\":\"pay-race\",\"status\":\"APPROVED\"}").delayed(500)
                : Response.status(404));

        List<ResponseEntity<JsonNode>> results = concurrently(8, i -> pay(orderId, uniqueKey(), "tok_ok"));

        assertNoServerErrors(results);
        assertThat(count(results, 200)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(7);
        results.stream().filter(r -> code(r) == 409).forEach(r -> assertProblem(r, 409, "INVALID_STATE"));
        assertThat(PG.requestsTo("/v1/payments")).hasSize(1);
        assertThat(statusOf(orderId)).isEqualTo("PAID");
        assertStock(productId, 7, 0);
    }

    @Test
    @DisplayName("R10.5 서로 다른 주문의 결제는 동시에 진행되어도 각자 정확히 1번씩 PG 로 간다")
    void r10_5_differentOrdersPayIndependently() {
        long productId = newProduct(1000, 50);
        List<Long> orderIds = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            orderIds.add(newOrder(productId, 1).get("id").asLong());
        }

        List<ResponseEntity<JsonNode>> results = concurrently(6, i -> pay(orderIds.get(i)));

        assertThat(count(results, 200)).isEqualTo(6);
        assertThat(PG.requestsTo("/v1/payments")).hasSize(6);
        Set<String> pgKeys = new HashSet<>();
        PG.requestsTo("/v1/payments").forEach(r -> pgKeys.add(r.header("Idempotency-Key")));
        assertThat(pgKeys).hasSize(6);
        assertStock(productId, 44, 0);
    }
}
