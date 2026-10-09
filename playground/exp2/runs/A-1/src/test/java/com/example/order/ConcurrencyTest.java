package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R10. 동시성")
class ConcurrencyTest extends IntegrationTestSupport {

    private static long count(List<ResponseEntity<JsonNode>> responses, int status) {
        return responses.stream().filter(r -> r.getStatusCode().value() == status).count();
    }

    private static long countCode(List<ResponseEntity<JsonNode>> responses, String code) {
        return responses.stream().filter(r -> code.equals(r.getBody().path("code").asText())).count();
    }

    @Test
    @DisplayName("R10.1 available 10인 상품에 수량 1 주문 20건 동시 → 201 10건, 409 10건, reserved 10")
    void stock() {
        long p = createProduct(1_000, 10);

        List<ResponseEntity<JsonNode>> responses = concurrently(20,
                i -> createOrder(newUser(), newKey(), orderBody(null, item(p, 1))));

        assertThat(count(responses, 201)).isEqualTo(10);
        assertThat(count(responses, 409)).isEqualTo(10);
        assertThat(countCode(responses, "INSUFFICIENT_STOCK")).isEqualTo(10);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(10);
        assertThat(product(p).get("available").asInt()).isZero();
    }

    @Test
    @DisplayName("R10.2 totalQuantity 5인 쿠폰을 서로 다른 사용자 15명이 동시 사용 → 201 5건, 409 10건, usedCount 5")
    void couponQuantity() {
        long p = createProduct(1_000, 100);
        String code = createCoupon("totalQuantity", 5);

        List<ResponseEntity<JsonNode>> responses = concurrently(15,
                i -> createOrder(newUser(), newKey(), orderBody(code, item(p, 1))));

        assertThat(count(responses, 201)).isEqualTo(5);
        assertThat(count(responses, 409)).isEqualTo(10);
        assertThat(countCode(responses, "COUPON_EXHAUSTED")).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(5);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("R10.3 한 사용자가 같은 쿠폰으로 주문 5건 동시 요청(키는 서로 다름) → 정확히 1건 201")
    void couponPerUser() {
        long p = createProduct(1_000, 100);
        String code = createCoupon("totalQuantity", 100);
        String user = newUser();

        List<ResponseEntity<JsonNode>> responses = concurrently(5,
                i -> createOrder(user, newKey(), orderBody(code, item(p, 1))));

        assertThat(count(responses, 201)).isEqualTo(1);
        assertThat(countCode(responses, "COUPON_NOT_APPLICABLE")).isEqualTo(4);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R10.4 [P, Q]와 [Q, P] 순서 주문을 섞어 동시에 요청해도 5xx 없이 처리되고 reserved가 정확하다")
    void noDeadlock() {
        long p = createProduct(1_000, 30);
        long q = createProduct(2_000, 30);

        List<ResponseEntity<JsonNode>> responses = concurrently(40, i -> {
            Map<String, Object> body = i % 2 == 0
                    ? orderBody(null, item(p, 1), item(q, 1))
                    : orderBody(null, item(q, 1), item(p, 1));
            return createOrder(newUser(), newKey(), body);
        });

        assertThat(responses).noneMatch(r -> r.getStatusCode().is5xxServerError());
        assertThat(count(responses, 201)).isEqualTo(30);
        assertThat(countCode(responses, "INSUFFICIENT_STOCK")).isEqualTo(10);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(30);
        assertThat(product(q).get("reserved").asInt()).isEqualTo(30);
    }

    @Test
    @DisplayName("R10.5 같은 주문에 결제 요청이 동시에 와도(키는 서로 다름) PG 결제 요청은 최대 1번, 성공 응답은 1건")
    void concurrentPayments() {
        long p = createProduct(1_000, 10);
        long orderId = placeOrder(newUser(), null, item(p, 2)).get("id").asLong();

        List<ResponseEntity<JsonNode>> responses = concurrently(8, i -> pay(orderId, newKey(), "slow-card"));

        assertThat(count(responses, 200)).isEqualTo(1);
        assertThat(countCode(responses, "INVALID_STATE")).isEqualTo(7);
        assertThat(PG.paymentCallsFor(orderId)).hasSizeLessThanOrEqualTo(1);
        assertThat(order(orderId).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asInt()).isEqualTo(8);
        assertThat(product(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R10 결제·취소가 동시에 와도 재고가 일관된다")
    void payAndCancelRace() {
        long p = createProduct(1_000, 10);
        long orderId = placeOrder(newUser(), null, item(p, 3)).get("id").asLong();

        List<ResponseEntity<JsonNode>> responses = concurrently(2, i -> i == 0
                ? pay(orderId, newKey(), "slow-card")
                : post("/api/orders/" + orderId + "/cancel", null));

        assertThat(responses).noneMatch(r -> r.getStatusCode().is5xxServerError());
        JsonNode product = product(p);
        String status = order(orderId).get("status").asText();
        assertThat(status).isIn("PAID", "CANCELLED", "REFUNDED");
        int expectedStock = status.equals("PAID") ? 7 : 10;
        assertThat(product.get("stock").asInt()).isEqualTo(expectedStock);
        assertThat(product.get("reserved").asInt()).isZero();
    }
}
