package com.example.order.api;

import static com.example.order.api.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiClient;
import com.example.order.support.IntegrationTestBase;
import com.example.order.support.PgStub.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("REQ-12/REQ-14 cancel, refund, ship, deliver and the state transition table")
class OrderCancelAndFulfillmentTest extends IntegrationTestBase {

    private ResponseEntity<JsonNode> act(String action, long orderId) {
        return switch (action) {
            case "pay" -> api.pay(orderId, ApiClient.uniq("pay"), "tok_ok");
            case "cancel" -> api.cancel(orderId);
            case "ship" -> api.ship(orderId);
            case "deliver" -> api.deliver(orderId);
            default -> throw new IllegalArgumentException(action);
        };
    }

    @Nested
    @DisplayName("REQ-12 취소 / 환불")
    class Cancel {

        @Test
        @DisplayName("REQ-12 PENDING_PAYMENT 취소 -> 200 CANCELLED, 재고 예약 해제, 쿠폰 복구, PG 미호출")
        void cancelPendingReleasesStockAndCoupon() {
            long p = api.newProduct(1000, 10);
            String coupon = api.newCoupon("FIXED", 100, null, null, 5);
            long orderId = api.orderOk(ApiClient.uniq("u"), p, 3, coupon).get("id").asLong();
            assertThat(api.product(p).get("reserved").asInt()).isEqualTo(3);
            assertThat(api.coupon(coupon).get("usedCount").asInt()).isEqualTo(1);

            ResponseEntity<JsonNode> res = api.cancel(orderId);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().get("status").asText()).isEqualTo("CANCELLED");
            assertThat(res.getBody().get("paidAt").isNull()).isTrue();
            assertThat(api.product(p).get("reserved").asInt()).isZero();
            assertThat(api.product(p).get("stock").asInt()).isEqualTo(10);
            assertThat(api.coupon(coupon).get("usedCount").asInt()).isZero();
            assertThat(PG.calls()).isEmpty();
        }

        @Test
        @DisplayName("REQ-12 취소로 해제된 재고는 다시 주문할 수 있다")
        void releasedStockCanBeOrderedAgain() {
            long p = api.newProduct(1000, 2);
            long first = api.orderOk(p, 2).get("id").asLong();
            api.cancel(first);

            ResponseEntity<JsonNode> again = api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), null,
                    ApiClient.item(p, 2));

            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("REQ-12 취소로 복구된 쿠폰 수량은 다시 사용할 수 있다 (totalQuantity=1)")
        void restoredCouponCanBeUsedAgain() {
            long p = api.newProduct(1000, 10);
            String coupon = api.newCoupon("FIXED", 100, null, null, 1);
            long first = api.orderOk(ApiClient.uniq("u"), p, 1, coupon).get("id").asLong();
            api.cancel(first);

            ResponseEntity<JsonNode> again = api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), coupon,
                    ApiClient.item(p, 1));

            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("REQ-12 CANCELLED 주문 재취소 -> 409 invalid-order-state (currentStatus=CANCELLED, requestedAction=cancel), 이중 해제 없음")
        void cancellingTwiceIsConflictAndDoesNotReleaseTwice() {
            long p = api.newProduct(1000, 10);
            long keep = api.orderOk(p, 4).get("id").asLong();
            long cancelled = api.orderOk(p, 2).get("id").asLong();
            api.cancel(cancelled);

            ResponseEntity<JsonNode> res = api.cancel(cancelled);

            assertProblem(res, 409, "invalid-order-state");
            assertThat(res.getBody().get("currentStatus").asText()).isEqualTo("CANCELLED");
            assertThat(res.getBody().get("requestedAction").asText()).isEqualTo("cancel");
            assertThat(api.product(p).get("reserved").asInt()).isEqualTo(4);
            assertThat(orderStatusInDb(keep)).isEqualTo("PENDING_PAYMENT");
        }

        @Test
        @DisplayName("REQ-12 PAID 취소 -> PG POST /v1/payments/{paymentId}/refund 호출 후 REFUNDED, 재고(stock) 복구, 쿠폰은 복구 안 함")
        void cancelPaidRefundsAndRestoresStockButNotCoupon() {
            long p = api.newProduct(1000, 10);
            String coupon = api.newCoupon("FIXED", 100, null, null, 5);
            long orderId = api.orderOk(ApiClient.uniq("u"), p, 3, coupon).get("id").asLong();
            PG.onApprove(c -> Reply.approved("pay-refundable-1"));
            JsonNode paid = api.payOk(orderId).getBody();
            assertThat(api.product(p).get("stock").asInt()).isEqualTo(7);

            ResponseEntity<JsonNode> res = api.cancel(orderId);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().get("status").asText()).isEqualTo("REFUNDED");
            assertThat(res.getBody().get("paidAt")).isEqualTo(paid.get("paidAt"));
            assertThat(PG.refundCalls()).hasSize(1);
            assertThat(PG.refundCalls().get(0).method()).isEqualTo("POST");
            assertThat(PG.refundCalls().get(0).path()).isEqualTo("/v1/payments/pay-refundable-1/refund");
            JsonNode product = api.product(p);
            assertThat(product.get("stock").asInt()).isEqualTo(10);
            assertThat(product.get("reserved").asInt()).isZero();
            assertThat(api.coupon(coupon).get("usedCount").asInt()).isEqualTo(1);
            Map<String, Object> row = jdbc.queryForMap("select * from payments where order_id = ?", orderId);
            assertThat(row.get("status")).isEqualTo("REFUNDED");
            assertThat(row.get("refunded_at")).isNotNull();
        }

        @Test
        @DisplayName("REQ-12 환불 PG 5xx -> 502, 주문은 PAID 유지, 재고/payments 불변")
        void refundServerErrorKeepsOrderPaid() {
            long p = api.newProduct(1000, 10);
            long orderId = api.orderOk(p, 2).get("id").asLong();
            api.payOk(orderId);
            PG.onRefund(c -> Reply.json(500, "{}"));

            ResponseEntity<JsonNode> res = api.cancel(orderId);

            assertProblem(res, 502, "payment-gateway-error");
            assertThat(res.getBody().get("reason").asText()).isEqualTo("SERVER_ERROR");
            assertThat(res.getBody().get("retryable").asBoolean()).isTrue();
            assertThat(orderStatusInDb(orderId)).isEqualTo("PAID");
            assertThat(api.product(p).get("stock").asInt()).isEqualTo(8);
            assertThat(jdbc.queryForObject("select status from payments where order_id = ?", String.class, orderId))
                    .isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("REQ-12 환불 PG 타임아웃 -> 504, 주문은 PAID 유지")
        void refundTimeoutKeepsOrderPaid() {
            long orderId = api.orderOk(api.newProduct(1000, 10), 1).get("id").asLong();
            api.payOk(orderId);
            PG.onRefund(c -> Reply.delayed(2500, Reply.refunded("x")));

            ResponseEntity<JsonNode> res = api.cancel(orderId);

            assertProblem(res, 504, "payment-gateway-timeout");
            assertThat(orderStatusInDb(orderId)).isEqualTo("PAID");
        }

        @Test
        @DisplayName("REQ-12 환불 응답 status 가 REFUNDED 가 아니면 502 BAD_RESPONSE, PAID 유지")
        void refundBadResponseKeepsOrderPaid() {
            long orderId = api.orderOk(api.newProduct(1000, 10), 1).get("id").asLong();
            api.payOk(orderId);
            PG.onRefund(c -> Reply.json(200, "{\"paymentId\":\"x\",\"status\":\"FAILED\"}"));

            ResponseEntity<JsonNode> res = api.cancel(orderId);

            assertProblem(res, 502, "payment-gateway-error");
            assertThat(res.getBody().get("reason").asText()).isEqualTo("BAD_RESPONSE");
            assertThat(orderStatusInDb(orderId)).isEqualTo("PAID");
        }

        @Test
        @DisplayName("REQ-12 환불 실패 후 같은 cancel 재시도 성공 -> REFUNDED, 재고는 정확히 1회만 복구")
        void refundRetrySucceedsAndRestoresStockOnce() {
            long p = api.newProduct(1000, 10);
            long orderId = api.orderOk(p, 2).get("id").asLong();
            api.payOk(orderId);
            AtomicInteger attempts = new AtomicInteger();
            PG.onRefund(c -> attempts.incrementAndGet() == 1 ? Reply.json(503, "{}") : Reply.refunded("ok"));

            ResponseEntity<JsonNode> failed = api.cancel(orderId);
            ResponseEntity<JsonNode> retried = api.cancel(orderId);

            assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(retried.getBody().get("status").asText()).isEqualTo("REFUNDED");
            assertThat(api.product(p).get("stock").asInt()).isEqualTo(10);
            assertThat(PG.refundCalls()).hasSize(2);
        }

        @Test
        @DisplayName("REQ-12 REFUNDED 재취소 -> 409 invalid-order-state, PG 환불 추가 호출 없음")
        void cancellingRefundedOrderIsConflict() {
            long orderId = api.orderInState("REFUNDED");
            PG.reset();

            ResponseEntity<JsonNode> res = api.cancel(orderId);

            assertProblem(res, 409, "invalid-order-state");
            assertThat(PG.calls()).isEmpty();
        }

        @Test
        @DisplayName("REQ-12 SHIPPED 취소 거부 -> 409 invalid-order-state, PG 미호출, 상태 유지")
        void cancellingShippedOrderIsRejected() {
            long orderId = api.orderInState("SHIPPED");
            PG.reset();

            ResponseEntity<JsonNode> res = api.cancel(orderId);

            assertProblem(res, 409, "invalid-order-state");
            assertThat(res.getBody().get("currentStatus").asText()).isEqualTo("SHIPPED");
            assertThat(PG.calls()).isEmpty();
            assertThat(orderStatusInDb(orderId)).isEqualTo("SHIPPED");
        }

        @Test
        @DisplayName("REQ-12 DELIVERED 취소 거부 -> 409")
        void cancellingDeliveredOrderIsRejected() {
            long orderId = api.orderInState("DELIVERED");

            ResponseEntity<JsonNode> res = api.cancel(orderId);

            assertProblem(res, 409, "invalid-order-state");
            assertThat(res.getBody().get("currentStatus").asText()).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("REQ-12 PAYMENT_FAILED 취소 거부 -> 409")
        void cancellingPaymentFailedOrderIsRejected() {
            long orderId = api.orderInState("PAYMENT_FAILED");

            ResponseEntity<JsonNode> res = api.cancel(orderId);

            assertProblem(res, 409, "invalid-order-state");
        }

        @Test
        @DisplayName("REQ-12 없는 주문 취소 -> 404 order-not-found")
        void cancelUnknownOrderIs404() {
            assertProblem(api.cancel(987654321L), 404, "order-not-found");
        }
    }

    @Nested
    @DisplayName("REQ-14 배송 상태 관리")
    class Fulfillment {

        @Test
        @DisplayName("REQ-14 ship: PAID -> SHIPPED (200, 주문 본문)")
        void shipMovesPaidToShipped() {
            long orderId = api.orderInState("PAID");

            ResponseEntity<JsonNode> res = api.ship(orderId);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().get("status").asText()).isEqualTo("SHIPPED");
            assertThat(res.getBody().get("id").asLong()).isEqualTo(orderId);
            assertThat(res.getBody().get("paidAt").isNull()).isFalse();
        }

        @Test
        @DisplayName("REQ-14 deliver: SHIPPED -> DELIVERED (200)")
        void deliverMovesShippedToDelivered() {
            long orderId = api.orderInState("SHIPPED");

            ResponseEntity<JsonNode> res = api.deliver(orderId);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().get("status").asText()).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("REQ-14 전체 흐름: 주문 -> 결제 -> 출고 -> 배송 완료 후 재고 확정 상태 유지")
        void fullHappyFlowEndsDeliveredWithConfirmedStock() {
            long p = api.newProduct(1000, 10);
            long orderId = api.orderOk(p, 4).get("id").asLong();

            api.payOk(orderId);
            api.ship(orderId);
            JsonNode delivered = api.deliver(orderId).getBody();

            assertThat(delivered.get("status").asText()).isEqualTo("DELIVERED");
            assertThat(api.order(orderId).get("status").asText()).isEqualTo("DELIVERED");
            assertThat(api.product(p).get("stock").asInt()).isEqualTo(6);
            assertThat(api.product(p).get("reserved").asInt()).isZero();
        }

        @Test
        @DisplayName("REQ-14 PENDING_PAYMENT 주문 ship -> 409 invalid-order-state (currentStatus/requestedAction=ship)")
        void shipPendingIsConflict() {
            long orderId = api.orderInState("PENDING_PAYMENT");

            ResponseEntity<JsonNode> res = api.ship(orderId);

            assertProblem(res, 409, "invalid-order-state");
            assertThat(res.getBody().get("currentStatus").asText()).isEqualTo("PENDING_PAYMENT");
            assertThat(res.getBody().get("requestedAction").asText()).isEqualTo("ship");
        }

        @Test
        @DisplayName("REQ-14 PAID 주문 deliver(출고 전) -> 409 invalid-order-state (requestedAction=deliver)")
        void deliverBeforeShipIsConflict() {
            long orderId = api.orderInState("PAID");

            ResponseEntity<JsonNode> res = api.deliver(orderId);

            assertProblem(res, 409, "invalid-order-state");
            assertThat(res.getBody().get("requestedAction").asText()).isEqualTo("deliver");
            assertThat(orderStatusInDb(orderId)).isEqualTo("PAID");
        }

        @Test
        @DisplayName("REQ-14 ship 재호출 / deliver 재호출 -> 409 (멱등 키 없음, 재호출은 거부)")
        void repeatedShipAndDeliverAreConflicts() {
            long shipped = api.orderInState("SHIPPED");
            long delivered = api.orderInState("DELIVERED");

            assertProblem(api.ship(shipped), 409, "invalid-order-state");
            assertProblem(api.deliver(delivered), 409, "invalid-order-state");
            assertThat(orderStatusInDb(shipped)).isEqualTo("SHIPPED");
            assertThat(orderStatusInDb(delivered)).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("REQ-14 없는 주문 ship/deliver -> 404, 숫자가 아닌 id -> 400 invalid-parameter")
        void unknownAndNonNumericIds() {
            assertProblem(api.ship(987654321L), 404, "order-not-found");
            assertProblem(api.deliver(987654321L), 404, "order-not-found");
            ResponseEntity<JsonNode> bad = api.post("/api/orders/xyz/ship", null);
            assertProblem(bad, 400, "invalid-parameter");
            assertThat(bad.getBody().get("parameter").asText()).isEqualTo("id");
        }
    }

    @Nested
    @DisplayName("REQ-14/REQ-12 상태 전이표: 표에 없는 (상태, 이벤트) 조합은 모두 409")
    class TransitionTable {

        private static final List<String> STATES = List.of("PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED",
                "CANCELLED", "REFUNDED", "PAYMENT_FAILED");
        private static final List<String> ACTIONS = List.of("pay", "cancel", "ship", "deliver");
        private static final Set<String> ALLOWED = Set.of("PENDING_PAYMENT:pay", "PENDING_PAYMENT:cancel",
                "PAID:cancel", "PAID:ship", "SHIPPED:deliver");

        static Stream<Arguments> disallowed() {
            List<Arguments> args = new ArrayList<>();
            for (String state : STATES) {
                for (String action : ACTIONS) {
                    if (!ALLOWED.contains(state + ":" + action)) {
                        args.add(Arguments.of(state, action));
                    }
                }
            }
            return args.stream();
        }

        @ParameterizedTest(name = "{0} + {1} -> 409")
        @MethodSource("disallowed")
        @DisplayName("REQ-14 허용되지 않은 전이는 409 invalid-order-state 이고 상태는 그대로")
        void disallowedTransitionIsConflictAndKeepsState(String state, String action) {
            long orderId = api.orderInState(state);
            PG.reset();

            ResponseEntity<JsonNode> res = act(action, orderId);

            assertProblem(res, 409, "invalid-order-state");
            assertThat(res.getBody().get("currentStatus").asText()).isEqualTo(state);
            assertThat(res.getBody().get("requestedAction").asText()).isEqualTo(action);
            assertThat(res.getBody().get("orderId").asLong()).isEqualTo(orderId);
            assertThat(orderStatusInDb(orderId)).isEqualTo(state);
            assertThat(PG.calls()).isEmpty();
        }
    }
}
