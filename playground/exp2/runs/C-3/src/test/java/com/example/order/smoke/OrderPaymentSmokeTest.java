package com.example.order.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 주문 생성 -> 결제 승인 해피패스 (+ 멱등 재생 1건). 엣지·에러 케이스는 Phase 3 test-writer 담당. */
class OrderPaymentSmokeTest extends IntegrationTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void createOrder_thenPayApproved_movesStockAndMarksPaid() {
        long productId = createProduct("monitor", 100_000, 5);

        ResponseEntity<String> created = createOrder("user-1", "key-create-1", orderBody(null, item(productId, 2)));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode order = json(created);
        long orderId = order.get("id").asLong();
        assertThat(created.getHeaders().getLocation()).hasToString("/api/orders/" + orderId);
        assertThat(order.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("totalPrice").asLong()).isEqualTo(200_000L);
        assertThat(order.get("paidAt").isNull()).isTrue();
        assertThat(json(getProduct(productId)).get("reserved").asInt()).isEqualTo(2);

        ResponseEntity<String> paid = pay(orderId, "key-pay-1", "tok_visa");

        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(paid).get("status").asText()).isEqualTo("PAID");
        assertThat(json(paid).get("paidAt").isNull()).isFalse();
        JsonNode product = json(getProduct(productId));
        assertThat(product.get("stock").asInt()).isEqualTo(3);
        assertThat(product.get("reserved").asInt()).isZero();

        // PG 계약: 요청 1회, Idempotency-Key 그대로, cardToken 그대로 전달
        assertThat(PG.paymentCalls()).isEqualTo(1);
        assertThat(PG.receivedPayments().get(0).idempotencyKey()).isEqualTo("key-pay-1");
        assertThat(PG.receivedPayments().get(0).body()).contains("tok_visa").contains("\"amount\":200000");

        // 같은 키·같은 요청 재전송 -> 최초 응답 재생, PG 재호출 없음
        ResponseEntity<String> replay = pay(orderId, "key-pay-1", "tok_visa");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(paid.getBody());
        assertThat(PG.paymentCalls()).isEqualTo(1);
    }

    @Test
    void createOrder_sameKeyReplay_returnsSameResponseWithoutSecondReservation() {
        long productId = createProduct("mouse", 10_000, 3);
        Object body = orderBody(null, item(productId, 1));

        ResponseEntity<String> first = createOrder("user-2", "key-create-replay", body);
        ResponseEntity<String> second = createOrder("user-2", "key-create-replay", body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(json(getProduct(productId)).get("reserved").asInt()).isEqualTo(1);
    }
}
