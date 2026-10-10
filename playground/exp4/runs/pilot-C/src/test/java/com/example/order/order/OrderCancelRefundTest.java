package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Recorded;
import com.example.order.support.FakePaymentGateway.Response;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

/** R7. 취소·환불 + 외부 PG 계약(환불 요청 부분). */
class OrderCancelRefundTest extends IntegrationTestBase {

    /** 결제 시 고정 paymentId 를 돌려주는 PG. 환불 요청은 기본 계약대로 응답한다. */
    private String pgIssuingPaymentId() {
        String paymentId = "pg-" + UUID.randomUUID();
        PG.respondWith(r -> {
            if (r.path().equals("/v1/payments")) {
                return Response.json("{\"paymentId\":\"" + paymentId + "\",\"status\":\"APPROVED\"}");
            }
            if (r.path().equals("/v1/payments/" + paymentId + "/refund")) {
                return Response.json("{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}");
            }
            return Response.status(404);
        });
        return paymentId;
    }

    private void assertRefundedUntouched(long orderId, long productId, int stock) {
        assertThat(statusOf(orderId)).isEqualTo("PAID");
        assertStock(productId, stock, 0);
    }

    // ------------------------------------------------------------------ R7.1 / R7.2 PENDING 취소

    @Test
    @DisplayName("R7.1/R7.2 PENDING_PAYMENT 취소는 200 CANCELLED, 예약·쿠폰 복원, PG 호출 없음, 본문은 주문 형태")
    void r7_2_cancelPendingRestoresReservationAndCoupon() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        JsonNode created = newOrder(uniqueUser(), code, items(productId, 4));
        long orderId = created.get("id").asLong();
        assertStock(productId, 10, 4);

        ResponseEntity<JsonNode> res = cancel(orderId);

        assertStatus(res, 200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(res.getBody().get("id").asLong()).isEqualTo(orderId);
        assertThat(res.getBody().get("items")).hasSize(1);
        assertThat(res.getBody().get("paidAt").isNull()).isTrue();
        assertThat(order(orderId)).isEqualTo(res.getBody());
        assertStock(productId, 10, 0);
        assertUsedCount(code, 0);
        assertThat(PG.requests()).isEmpty();
    }

    @Test
    @DisplayName("R7.1 없는 주문 취소는 404 ORDER_NOT_FOUND")
    void r7_1_unknownOrderReturns404() {
        assertProblem(cancel(999_999_999L), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R7.1 orderId 가 숫자가 아니면 400")
    void r7_1_nonNumericOrderIdRejected() {
        assertProblem(post("/api/orders/abc/cancel", null), 400, "VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------ R7.3 PAID 환불

    @Test
    @DisplayName("R7.3 PAID 취소는 PG 환불 성공 시 200 REFUNDED, stock 증가(reserved 불변), 쿠폰 복원")
    void r7_3_cancelPaidRefundsAndRestoresStockAndCoupon() {
        pgIssuingPaymentId();
        long a = newProduct(1000, 10);
        long b = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(a, 3, b, 2)).get("id").asLong();
        payOk(orderId);
        assertStock(a, 7, 0);
        assertStock(b, 8, 0);

        ResponseEntity<JsonNode> res = cancel(orderId);

        assertStatus(res, 200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(order(orderId)).isEqualTo(res.getBody());
        assertStock(a, 10, 0);
        assertStock(b, 10, 0);
        assertUsedCount(code, 0);
    }

    @Test
    @DisplayName("R7.3/PG 환불은 POST /v1/payments/{결제 때 받은 paymentId}/refund 로 정확히 1회 요청된다")
    void r7_3_refundRequestContract() {
        String paymentId = pgIssuingPaymentId();
        long orderId = newOrder(newProduct(1000, 10), 1).get("id").asLong();
        payOk(orderId);

        assertStatus(cancel(orderId), 200);

        List<Recorded> refunds = PG.requestsTo("/v1/payments/" + paymentId + "/refund");
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).method()).isEqualTo("POST");
        assertThat(PG.requestsTo("/v1/payments")).hasSize(2); // 결제 1 + 환불 1
    }

    @Test
    @DisplayName("R7.3 환불된 쿠폰은 같은 사용자가 다시 쓸 수 있다")
    void r7_3_couponReusableAfterRefund() {
        String code = newCoupon("FIXED", 100, 5);
        long productId = newProduct(1000, 10);
        String user = uniqueUser();
        long orderId = newOrder(user, code, items(productId, 1)).get("id").asLong();
        payOk(orderId);
        assertStatus(cancel(orderId), 200);

        assertThat(code(placeOrder(user, code, productId, 1))).isEqualTo(201);
    }

    @ParameterizedTest(name = "R7.3 환불 PG 가 {0} 응답이면 503, 주문 PAID·재고·쿠폰 불변")
    @ValueSource(ints = {500, 502, 503})
    void r7_3_refundGateway5xxLeavesEverythingUnchanged(int pgStatus) {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 2)).get("id").asLong();
        payOk(orderId);
        PG.respondWith(r -> Response.status(pgStatus));

        ResponseEntity<JsonNode> res = cancel(orderId);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertRefundedUntouched(orderId, productId, 8);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R7.3 환불 PG 가 연결을 끊으면 503, 주문 PAID·재고·쿠폰 불변")
    void r7_3_refundConnectionDroppedLeavesEverythingUnchanged() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 2)).get("id").asLong();
        payOk(orderId);
        PG.respondWith(r -> Response.drop());

        assertProblem(cancel(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertRefundedUntouched(orderId, productId, 8);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R7.3 환불 PG 가 2초 안에 응답하지 않으면 503 (대략 2초대), 주문 PAID·재고·쿠폰 불변")
    void r7_3_refundTimeoutAfterTwoSeconds() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 2)).get("id").asLong();
        payOk(orderId);
        PG.respondWith(r -> Response.json("{\"paymentId\":\"x\",\"status\":\"REFUNDED\"}").delayed(6000));

        long startedAt = System.nanoTime();
        ResponseEntity<JsonNode> res = cancel(orderId);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isBetween(Duration.ofMillis(1900), Duration.ofMillis(3500));
        assertRefundedUntouched(orderId, productId, 8);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R7.3 환불 PG 장애 후 PG 가 복구되면 같은 주문을 다시 취소(환불)할 수 있다")
    void r7_3_refundRetryableAfterGatewayRecovery() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 2).get("id").asLong();
        payOk(orderId);
        PG.respondWith(r -> Response.status(500));
        assertProblem(cancel(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        PG.reset();

        ResponseEntity<JsonNode> retry = cancel(orderId);

        assertStatus(retry, 200);
        assertThat(retry.getBody().get("status").asText()).isEqualTo("REFUNDED");
        assertStock(productId, 10, 0);
    }

    // ------------------------------------------------------------------ R7.4 그 밖의 상태

    @Test
    @DisplayName("R7.4 이미 CANCELLED 인 주문 취소는 409 INVALID_STATE")
    void r7_4_cancelAlreadyCancelledReturns409() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 2).get("id").asLong();
        assertStatus(cancel(orderId), 200);

        assertProblem(cancel(orderId), 409, "INVALID_STATE");

        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R7.4 이미 REFUNDED 인 주문 취소는 409 INVALID_STATE, 환불 PG 를 다시 부르지 않고 재고는 한 번만 늘어난다")
    void r7_4_cancelAlreadyRefundedReturns409() {
        String paymentId = pgIssuingPaymentId();
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 2).get("id").asLong();
        payOk(orderId);
        assertStatus(cancel(orderId), 200);

        assertProblem(cancel(orderId), 409, "INVALID_STATE");

        assertThat(PG.requestsTo("/v1/payments/" + paymentId + "/refund")).hasSize(1);
        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R7.4 PAYMENT_FAILED 주문 취소는 409 INVALID_STATE")
    void r7_4_cancelPaymentFailedReturns409() {
        long orderId = newOrder(newProduct(1000, 10), 1).get("id").asLong();
        pay(orderId, uniqueKey(), "decline_card");

        assertProblem(cancel(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R7.4 EXPIRED 주문 취소는 409 INVALID_STATE (만료 시각을 과거로 당긴 주문), 예약은 한 번만 복원")
    void r7_4_cancelExpiredReturns409() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 2).get("id").asLong();
        jdbc.update("update orders set created_at = created_at - interval '1 hour', "
                + "expires_at = now() - interval '1 minute' where id = ?", orderId);

        assertProblem(cancel(orderId), 409, "INVALID_STATE");

        assertThat(statusOf(orderId)).isEqualTo("EXPIRED");
        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R7.4 SHIPPED 주문 취소는 409 INVALID_STATE, 환불 PG 호출 없음")
    void r7_4_cancelShippedReturns409() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 2).get("id").asLong();
        payOk(orderId);
        assertStatus(ship(orderId), 200);
        int pgCallsBefore = PG.requests().size();

        assertProblem(cancel(orderId), 409, "INVALID_STATE");

        assertThat(PG.requests()).hasSize(pgCallsBefore);
        assertThat(statusOf(orderId)).isEqualTo("SHIPPED");
        assertStock(productId, 8, 0);
    }

    @Test
    @DisplayName("R7.4 DELIVERED 주문 취소는 409 INVALID_STATE, 쿠폰은 복원되지 않는다")
    void r7_4_cancelDeliveredReturns409() {
        String code = newCoupon("FIXED", 100, 5);
        long productId = newProduct(1000, 10);
        long orderId = newOrder(uniqueUser(), code, items(productId, 2)).get("id").asLong();
        payOk(orderId);
        assertStatus(ship(orderId), 200);
        assertStatus(deliver(orderId), 200);

        assertProblem(cancel(orderId), 409, "INVALID_STATE");

        assertThat(statusOf(orderId)).isEqualTo("DELIVERED");
        assertStock(productId, 8, 0);
        assertUsedCount(code, 1);
    }
}
