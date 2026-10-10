package com.example.order.api;

import static com.example.order.api.ProblemAssertions.assertProblem;
import static com.example.order.support.ApiClient.item;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiClient;
import com.example.order.support.IntegrationTestBase;
import com.example.order.support.PgStub.Call;
import com.example.order.support.PgStub.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@DisplayName("REQ-10/REQ-11 payment and PG failure handling")
class OrderPaymentTest extends IntegrationTestBase {

    private Map<String, Object> payment(long orderId) {
        return jdbc.queryForMap("select * from payments where order_id = ?", orderId);
    }

    private int paymentRows(long orderId) {
        return jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, orderId);
    }

    private int payKeyRows(String key) {
        return jdbc.queryForObject("select count(*) from idempotency_keys where operation = 'PAY' and idem_key = ?",
                Integer.class, key);
    }

    @Nested
    @DisplayName("REQ-10 결제 승인/거절")
    class Approval {

        @Test
        @DisplayName("REQ-10 APPROVED -> 200 PAID + paidAt, 재고 확정 차감(stock-=q, reserved-=q), payments(APPROVED) 저장")
        void approvedMarksPaidAndConfirmsStock() {
            long p = api.newProduct(1000, 10);
            long orderId = api.orderOk(p, 3).get("id").asLong();
            PG.onApprove(c -> Reply.approved("pay-abc"));

            ResponseEntity<JsonNode> res = api.pay(orderId, "pk-" + ApiClient.uniq("a"), "tok_ok");

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getHeaders().getFirst("Idempotent-Replayed")).isNull();
            assertThat(res.getBody().get("status").asText()).isEqualTo("PAID");
            assertThat(res.getBody().get("paidAt").isNull()).isFalse();
            JsonNode product = api.product(p);
            assertThat(product.get("stock").asInt()).isEqualTo(7);
            assertThat(product.get("reserved").asInt()).isZero();
            assertThat(product.get("available").asInt()).isEqualTo(7);
            Map<String, Object> row = payment(orderId);
            assertThat(row.get("payment_id")).isEqualTo("pay-abc");
            assertThat(row.get("status")).isEqualTo("APPROVED");
            assertThat(((Number) row.get("amount")).longValue()).isEqualTo(3000);
        }

        @Test
        @DisplayName("REQ-10 PG 요청: POST /v1/payments, Idempotency-Key 전달, 본문 {orderId(number), amount=totalPrice, cardToken}")
        void sendsExpectedRequestToGateway() {
            long p = api.newProduct(10000, 10);
            String coupon = api.newCoupon("FIXED", 2500, null, null, 5);
            JsonNode order = api.orderOk(ApiClient.uniq("u"), p, 2, coupon);
            String key = ApiClient.uniq("pay");

            api.pay(order.get("id").asLong(), key, "tok_card_1");

            assertThat(PG.approveCalls()).hasSize(1);
            Call call = PG.approveCalls().get(0);
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.path()).isEqualTo("/v1/payments");
            assertThat(call.idempotencyKey()).isEqualTo(key);
            assertThat(call.body().get("orderId").isNumber()).isTrue();
            assertThat(call.body().get("orderId").asLong()).isEqualTo(order.get("id").asLong());
            assertThat(call.body().get("amount").asLong()).isEqualTo(17500);
            assertThat(call.body().get("cardToken").asText()).isEqualTo("tok_card_1");
        }

        @Test
        @DisplayName("REQ-10 totalPrice=0 (전액 할인) 주문도 amount=0 으로 PG 를 호출하고 PAID")
        void zeroAmountOrderStillCallsGateway() {
            long p = api.newProduct(1000, 10);
            String coupon = api.newCoupon("RATE", 100, null, null, 5);
            long orderId = api.orderOk(ApiClient.uniq("u"), p, 1, coupon).get("id").asLong();

            ResponseEntity<JsonNode> res = api.payOk(orderId);

            assertThat(res.getBody().get("status").asText()).isEqualTo("PAID");
            assertThat(PG.approveCalls()).hasSize(1);
            assertThat(PG.approveCalls().get(0).body().get("amount").asLong()).isZero();
        }

        @Test
        @DisplayName("REQ-10 결제 성공 후 쿠폰 사용 횟수는 유지된다")
        void approvedKeepsCouponConsumed() {
            long p = api.newProduct(1000, 10);
            String coupon = api.newCoupon("FIXED", 100, null, null, 5);
            long orderId = api.orderOk(ApiClient.uniq("u"), p, 1, coupon).get("id").asLong();

            api.payOk(orderId);

            assertThat(api.coupon(coupon).get("usedCount").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("REQ-10 DECLINED -> 200 + PAYMENT_FAILED (오류 아님), paidAt=null, 예약 재고 해제, 쿠폰 복구, payments(DECLINED)")
        void declinedMarksPaymentFailedAndReleasesResources() {
            long p = api.newProduct(1000, 10);
            String coupon = api.newCoupon("FIXED", 100, null, null, 5);
            long orderId = api.orderOk(ApiClient.uniq("u"), p, 4, coupon).get("id").asLong();

            ResponseEntity<JsonNode> res = api.pay(orderId, ApiClient.uniq("pay"), "tok_decline");

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().get("status").asText()).isEqualTo("PAYMENT_FAILED");
            assertThat(res.getBody().get("paidAt").isNull()).isTrue();
            JsonNode product = api.product(p);
            assertThat(product.get("stock").asInt()).isEqualTo(10);
            assertThat(product.get("reserved").asInt()).isZero();
            assertThat(api.coupon(coupon).get("usedCount").asInt()).isZero();
            assertThat(payment(orderId).get("status")).isEqualTo("DECLINED");
        }

        @Test
        @DisplayName("REQ-10 PAYMENT_FAILED 주문을 새 키로 다시 결제 -> 409 invalid-order-state (currentStatus/requestedAction)")
        void payingFailedOrderAgainIsInvalidState() {
            long orderId = api.orderInState("PAYMENT_FAILED");
            PG.reset();

            ResponseEntity<JsonNode> res = api.payOk(orderId);

            assertProblem(res, 409, "invalid-order-state");
            assertThat(res.getBody().get("currentStatus").asText()).isEqualTo("PAYMENT_FAILED");
            assertThat(res.getBody().get("requestedAction").asText()).isEqualTo("pay");
            assertThat(res.getBody().get("orderId").asLong()).isEqualTo(orderId);
            assertThat(PG.calls()).isEmpty();
        }

        @Test
        @DisplayName("REQ-10 이미 PAID 인 주문에 새 키로 결제 -> 409 invalid-order-state, PG 재호출 없음, 재고 이중 차감 없음")
        void payingPaidOrderAgainIsInvalidState() {
            long p = api.newProduct(1000, 10);
            long orderId = api.orderOk(p, 2).get("id").asLong();
            api.payOk(orderId);
            PG.reset();

            ResponseEntity<JsonNode> res = api.payOk(orderId);

            assertProblem(res, 409, "invalid-order-state");
            assertThat(res.getBody().get("currentStatus").asText()).isEqualTo("PAID");
            assertThat(PG.calls()).isEmpty();
            assertThat(api.product(p).get("stock").asInt()).isEqualTo(8);
        }

        @Test
        @DisplayName("REQ-10 CANCELLED 주문 결제 -> 409 invalid-order-state")
        void payingCancelledOrderIsInvalidState() {
            long orderId = api.orderInState("CANCELLED");

            ResponseEntity<JsonNode> res = api.payOk(orderId);

            assertProblem(res, 409, "invalid-order-state");
            assertThat(res.getBody().get("currentStatus").asText()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("REQ-10 없는 주문 결제 -> 404 order-not-found")
        void payingUnknownOrderIs404() {
            ResponseEntity<JsonNode> res = api.payOk(987654321L);

            assertProblem(res, 404, "order-not-found");
        }

        @Test
        @DisplayName("REQ-10 숫자가 아닌 주문 id -> 400 invalid-parameter (parameter=id)")
        void payingNonNumericOrderIsInvalidParameter() {
            ResponseEntity<JsonNode> res = api.post("/api/orders/abc/pay", Map.of("cardToken", "t"), "Idempotency-Key",
                    "k");

            assertProblem(res, 400, "invalid-parameter");
            assertThat(res.getBody().get("parameter").asText()).isEqualTo("id");
        }
    }

    @Nested
    @DisplayName("REQ-10/15 결제 요청 검증")
    class Validation {

        private long newOrder() {
            return api.orderOk(api.newProduct(1000, 10), 1).get("id").asLong();
        }

        @Test
        @DisplayName("REQ-15 Idempotency-Key 누락 -> 400 missing-required-header, PG 호출 없음")
        void missingIdempotencyKeyIsBadRequest() {
            long orderId = newOrder();

            ResponseEntity<JsonNode> res = api.post("/api/orders/" + orderId + "/pay", Map.of("cardToken", "t"));

            assertProblem(res, 400, "missing-required-header");
            assertThat(res.getBody().get("header").asText()).isEqualTo("Idempotency-Key");
            assertThat(PG.calls()).isEmpty();
        }

        @Test
        @DisplayName("REQ-10 cardToken 공백 -> 400 validation-failed (field=cardToken)")
        void blankCardTokenIsValidationFailed() {
            long orderId = newOrder();

            ResponseEntity<JsonNode> res = api.pay(orderId, ApiClient.uniq("pay"), "  ");

            assertProblem(res, 400, "validation-failed");
            assertThat(ApiClient.fieldsOf(res.getBody())).contains("cardToken");
            assertThat(PG.calls()).isEmpty();
        }

        @Test
        @DisplayName("REQ-10 cardToken 201자 -> 400 validation-failed, 200자는 허용")
        void cardTokenLengthBoundary() {
            long orderId = newOrder();

            ResponseEntity<JsonNode> tooLong = api.pay(orderId, ApiClient.uniq("pay"), "t".repeat(201));
            ResponseEntity<JsonNode> ok = api.pay(orderId, ApiClient.uniq("pay"), "t".repeat(200));

            assertProblem(tooLong, 400, "validation-failed");
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("REQ-10 본문 없음 / 깨진 JSON -> 400 malformed-request, 응답에 cardToken 이 노출되지 않는다")
        void missingOrBrokenBodyIsMalformedAndDoesNotEchoToken() {
            long orderId = newOrder();

            ResponseEntity<JsonNode> noBody = api.exchange(HttpMethod.POST, "/api/orders/" + orderId + "/pay", null,
                    MediaType.APPLICATION_JSON, "Idempotency-Key", ApiClient.uniq("pay"));
            ResponseEntity<JsonNode> broken = api.exchange(HttpMethod.POST, "/api/orders/" + orderId + "/pay",
                    "{\"cardToken\":\"super-secret-token\"", MediaType.APPLICATION_JSON, "Idempotency-Key",
                    ApiClient.uniq("pay"));

            assertProblem(noBody, 400, "malformed-request");
            assertProblem(broken, 400, "malformed-request");
            assertThat(broken.getBody().toString()).doesNotContain("super-secret-token");
        }

        @Test
        @DisplayName("REQ-10 cardToken 은 저장되지 않는다 (payments 컬럼/idempotency 해시에 평문 없음)")
        void cardTokenIsNotPersisted() {
            long orderId = newOrder();
            String key = ApiClient.uniq("pay");

            api.pay(orderId, key, "plaintext-card-token-xyz");

            assertThat(payment(orderId).values().toString()).doesNotContain("plaintext-card-token-xyz");
            assertThat(jdbc.queryForMap("select * from idempotency_keys where idem_key = ?", key).values().toString())
                    .doesNotContain("plaintext-card-token-xyz");
        }
    }

    @Nested
    @DisplayName("REQ-11 결제 멱등성")
    class PayIdempotency {

        @Test
        @DisplayName("REQ-11 같은 키 + 같은 본문 재요청 -> PG 1회만 호출, 200 + Idempotent-Replayed, 재고 1회만 차감")
        void sameKeyReplaysWithoutCallingGatewayAgain() {
            long p = api.newProduct(1000, 10);
            long orderId = api.orderOk(p, 2).get("id").asLong();
            String key = ApiClient.uniq("pay");
            ResponseEntity<JsonNode> first = api.pay(orderId, key, "tok_ok");

            ResponseEntity<JsonNode> replay = api.pay(orderId, key, "tok_ok");

            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replay.getBody().get("status").asText()).isEqualTo("PAID");
            assertThat(replay.getBody().get("paidAt")).isEqualTo(first.getBody().get("paidAt"));
            assertThat(PG.approveCalls()).hasSize(1);
            assertThat(api.product(p).get("stock").asInt()).isEqualTo(8);
            assertThat(paymentRows(orderId)).isEqualTo(1);
        }

        @Test
        @DisplayName("REQ-11 DECLINED 결과도 같은 키로 재요청하면 PG 재호출 없이 PAYMENT_FAILED 재생")
        void declinedResultIsReplayed() {
            long orderId = api.orderOk(api.newProduct(1000, 10), 1).get("id").asLong();
            String key = ApiClient.uniq("pay");
            api.pay(orderId, key, "tok_decline");

            ResponseEntity<JsonNode> replay = api.pay(orderId, key, "tok_decline");

            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replay.getBody().get("status").asText()).isEqualTo("PAYMENT_FAILED");
            assertThat(PG.approveCalls()).hasSize(1);
        }

        @Test
        @DisplayName("REQ-11 재생은 주문의 현재 상태를 돌려준다 (결제 후 ship -> 재생 응답은 SHIPPED)")
        void replayReturnsCurrentOrderState() {
            long orderId = api.orderOk(api.newProduct(1000, 10), 1).get("id").asLong();
            String key = ApiClient.uniq("pay");
            api.pay(orderId, key, "tok_ok");
            api.ship(orderId);

            ResponseEntity<JsonNode> replay = api.pay(orderId, key, "tok_ok");

            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getBody().get("status").asText()).isEqualTo("SHIPPED");
            assertThat(PG.approveCalls()).hasSize(1);
        }

        @Test
        @DisplayName("REQ-11 같은 키 + 다른 cardToken -> 409 idempotency-key-reused, PG 추가 호출 없음")
        void sameKeyDifferentTokenIsConflict() {
            long orderId = api.orderOk(api.newProduct(1000, 10), 1).get("id").asLong();
            String key = ApiClient.uniq("pay");
            api.pay(orderId, key, "tok_ok");

            ResponseEntity<JsonNode> res = api.pay(orderId, key, "tok_other");

            assertProblem(res, 409, "idempotency-key-reused");
            assertThat(res.getBody().get("idempotencyKey").asText()).isEqualTo(key);
            assertThat(PG.approveCalls()).hasSize(1);
        }

        @Test
        @DisplayName("REQ-11 결제 키가 다른 주문에 재사용되면 409 idempotency-key-reused, 그 주문은 PENDING 유지 + PG 미호출")
        void paymentKeyReusedOnAnotherOrderIsConflict() {
            long p = api.newProduct(1000, 10);
            long first = api.orderOk(p, 1).get("id").asLong();
            long second = api.orderOk(p, 1).get("id").asLong();
            String key = ApiClient.uniq("pay");
            api.pay(first, key, "tok_ok");

            ResponseEntity<JsonNode> res = api.pay(second, key, "tok_ok");

            assertProblem(res, 409, "idempotency-key-reused");
            assertThat(orderStatusInDb(second)).isEqualTo("PENDING_PAYMENT");
            assertThat(PG.approveCalls()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("REQ-11 PG 장애 처리 (롤백 + 재시도 가능)")
    class GatewayFailures {

        private long orderId;
        private long productId;
        private String key;

        private void givenOrder() {
            productId = api.newProduct(1000, 10);
            orderId = api.orderOk(productId, 2).get("id").asLong();
            key = ApiClient.uniq("pay");
        }

        private void assertNothingChanged() {
            assertThat(orderStatusInDb(orderId)).isEqualTo("PENDING_PAYMENT");
            JsonNode product = api.product(productId);
            assertThat(product.get("stock").asInt()).isEqualTo(10);
            assertThat(product.get("reserved").asInt()).isEqualTo(2);
            assertThat(paymentRows(orderId)).isZero();
            assertThat(payKeyRows(key)).isZero();
            assertThat(api.order(orderId).get("paidAt").isNull()).isTrue();
        }

        @Test
        @DisplayName("REQ-11 PG 5xx -> 502 payment-gateway-error reason=SERVER_ERROR retryable=true, 주문/재고/키 불변")
        void gatewayServerErrorIs502AndRollsBack() {
            givenOrder();
            PG.onApprove(c -> Reply.json(500, "{\"error\":\"boom\"}"));

            ResponseEntity<JsonNode> res = api.pay(orderId, key, "tok_ok");

            assertProblem(res, 502, "payment-gateway-error");
            assertThat(res.getBody().get("reason").asText()).isEqualTo("SERVER_ERROR");
            assertThat(res.getBody().get("retryable").asBoolean()).isTrue();
            assertNothingChanged();
        }

        @Test
        @DisplayName("REQ-11 PG 4xx -> 502 reason=REJECTED, 주문 불변")
        void gatewayClientErrorIs502Rejected() {
            givenOrder();
            PG.onApprove(c -> Reply.json(422, "{\"error\":\"bad\"}"));

            ResponseEntity<JsonNode> res = api.pay(orderId, key, "tok_ok");

            assertProblem(res, 502, "payment-gateway-error");
            assertThat(res.getBody().get("reason").asText()).isEqualTo("REJECTED");
            assertNothingChanged();
        }

        @Test
        @DisplayName("REQ-11 PG 읽기 타임아웃 -> 504 payment-gateway-timeout retryable=true, 주문/재고/키 불변")
        void gatewayTimeoutIs504AndRollsBack() {
            givenOrder();
            PG.onApprove(c -> Reply.delayed(2500, Reply.approved("late")));

            ResponseEntity<JsonNode> res = api.pay(orderId, key, "tok_ok");

            assertProblem(res, 504, "payment-gateway-timeout");
            assertThat(res.getBody().get("retryable").asBoolean()).isTrue();
            assertNothingChanged();
        }

        @Test
        @DisplayName("REQ-11 PG 가 응답 없이 연결을 끊음 -> 502 reason=UNAVAILABLE, 주문 불변")
        void droppedConnectionIs502Unavailable() {
            givenOrder();
            PG.onApprove(c -> Reply.drop());

            ResponseEntity<JsonNode> res = api.pay(orderId, key, "tok_ok");

            assertProblem(res, 502, "payment-gateway-error");
            assertThat(res.getBody().get("reason").asText()).isEqualTo("UNAVAILABLE");
            assertNothingChanged();
        }

        @Test
        @DisplayName("REQ-11 PG 200 이지만 status 불명 -> 502 reason=BAD_RESPONSE, 주문 불변")
        void unknownStatusIsBadResponse() {
            givenOrder();
            PG.onApprove(c -> Reply.json(200, "{\"paymentId\":\"x\",\"status\":\"PENDING\"}"));

            ResponseEntity<JsonNode> res = api.pay(orderId, key, "tok_ok");

            assertProblem(res, 502, "payment-gateway-error");
            assertThat(res.getBody().get("reason").asText()).isEqualTo("BAD_RESPONSE");
            assertNothingChanged();
        }

        @Test
        @DisplayName("REQ-11 PG 200 이지만 paymentId 누락 / 본문이 JSON 아님 -> 502 BAD_RESPONSE")
        void missingPaymentIdOrNonJsonIsBadResponse() {
            givenOrder();
            PG.onApprove(c -> Reply.json(200, "{\"status\":\"APPROVED\"}"));
            ResponseEntity<JsonNode> noId = api.pay(orderId, key, "tok_ok");
            PG.onApprove(c -> Reply.json(200, "<html>oops</html>"));
            ResponseEntity<JsonNode> html = api.pay(orderId, key, "tok_ok");

            assertProblem(noId, 502, "payment-gateway-error");
            assertThat(noId.getBody().get("reason").asText()).isEqualTo("BAD_RESPONSE");
            assertProblem(html, 502, "payment-gateway-error");
            assertThat(html.getBody().get("reason").asText()).isEqualTo("BAD_RESPONSE");
            assertNothingChanged();
        }

        @Test
        @DisplayName("REQ-11 5xx 실패 후 같은 키로 재시도하면 PG 를 다시 호출하고(같은 Idempotency-Key 전달) 성공 시 PAID")
        void retryWithSameKeyAfterFailureCallsGatewayAgain() {
            givenOrder();
            AtomicInteger attempts = new AtomicInteger();
            PG.onApprove(c -> attempts.incrementAndGet() == 1 ? Reply.json(503, "{}") : Reply.approved("pay-retry"));

            ResponseEntity<JsonNode> failed = api.pay(orderId, key, "tok_ok");
            ResponseEntity<JsonNode> retried = api.pay(orderId, key, "tok_ok");

            assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(retried.getHeaders().getFirst("Idempotent-Replayed")).isNull();
            assertThat(retried.getBody().get("status").asText()).isEqualTo("PAID");
            assertThat(PG.approveCalls()).hasSize(2);
            assertThat(PG.approveCalls()).extracting(Call::idempotencyKey).containsOnly(key);
            assertThat(api.product(productId).get("stock").asInt()).isEqualTo(8);
            assertThat(paymentRows(orderId)).isEqualTo(1);
        }

        @Test
        @DisplayName("REQ-11 타임아웃 후 재시도 성공 -> 이중 결제 없음 (payments 1건, 재고 1회 차감)")
        void retryAfterTimeoutDoesNotDoubleCharge() {
            givenOrder();
            AtomicInteger attempts = new AtomicInteger();
            PG.onApprove(c -> attempts.incrementAndGet() == 1 ? Reply.delayed(2500, Reply.approved("late"))
                    : Reply.approved("pay-ok"));

            ResponseEntity<JsonNode> timedOut = api.pay(orderId, key, "tok_ok");
            ResponseEntity<JsonNode> retried = api.pay(orderId, key, "tok_ok");

            assertThat(timedOut.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(retried.getBody().get("status").asText()).isEqualTo("PAID");
            assertThat(paymentRows(orderId)).isEqualTo(1);
            assertThat(payment(orderId).get("payment_id")).isEqualTo("pay-ok");
            assertThat(api.product(productId).get("stock").asInt()).isEqualTo(8);
            assertThat(api.product(productId).get("reserved").asInt()).isZero();
        }

        @Test
        @DisplayName("REQ-11 장애 후 다른 키로도 재시도 가능하다 (주문이 PENDING 으로 남아 있으므로)")
        void retryWithNewKeyAfterFailureSucceeds() {
            givenOrder();
            PG.onApprove(c -> Reply.json(500, "{}"));
            api.pay(orderId, key, "tok_ok");
            PG.reset();

            ResponseEntity<JsonNode> retried = api.pay(orderId, ApiClient.uniq("pay2"), "tok_ok");

            assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(retried.getBody().get("status").asText()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("REQ-11 paymentId 는 payments 에 저장되고 주문 응답에는 노출되지 않는다 (D-18)")
        void paymentIdIsStoredButNotExposed() {
            givenOrder();
            PG.onApprove(c -> Reply.approved("pg-visible-in-db"));

            ResponseEntity<JsonNode> res = api.pay(orderId, key, "tok_ok");

            assertThat(payment(orderId).get("payment_id")).isEqualTo("pg-visible-in-db");
            assertThat(res.getBody().has("paymentId")).isFalse();
        }
    }

    @Test
    @DisplayName("REQ-10 주문 생성 직후 PENDING 재고 예약 중에 다른 주문이 같은 상품을 가져가도 결제 확정은 영향 없음")
    void confirmationIsUnaffectedByOtherPendingReservations() {
        long p = api.newProduct(1000, 5);
        long mine = api.orderOk(p, 2).get("id").asLong();
        api.placeOrder(ApiClient.uniq("u"), ApiClient.uniq("k"), null, item(p, 3));

        api.payOk(mine);

        JsonNode product = api.product(p);
        assertThat(product.get("stock").asInt()).isEqualTo(3);
        assertThat(product.get("reserved").asInt()).isEqualTo(3);
        assertThat(product.get("available").asInt()).isZero();
    }
}
