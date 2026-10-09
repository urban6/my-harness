package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PG에 연결할 수 없는 환경. 결제는 PG 테스트 서버로, 환불 대상 주문은 DB에 직접 PAID로 만든다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "payment.gateway.url=http://127.0.0.1:1")
@DisplayName("R5.6/R7.3 PG 연결 실패")
class PaymentGatewayConnectionFailureTest extends IntegrationTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("R5.6 연결에 실패하면 503, 주문·재고·쿠폰은 바뀌지 않는다")
    void paymentConnectionFailure() {
        long p = createProduct(5_000, 10);
        String code = createCoupon();
        long orderId = placeOrder(newUser(), code, item(p, 2)).get("id").asLong();

        assertProblem(pay(orderId, newKey(), "card-ok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(order(orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(product(p).get("reserved").asInt()).isEqualTo(2);
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.3 환불 요청 연결에 실패하면 503, 주문·재고·쿠폰은 바뀌지 않는다")
    void refundConnectionFailure() {
        long p = createProduct(5_000, 10);
        String code = createCoupon();
        long orderId = placeOrder(newUser(), code, item(p, 2)).get("id").asLong();
        // 결제 승인 상태를 직접 만든다 (stock 10 → 8, reserved 0)
        jdbc.update("update orders set status = 'PAID', paid_at = now(), payment_id = 'pay_x' where id = ?", orderId);
        jdbc.update("update products set stock = stock - 2, reserved = reserved - 2 where id = ?", p);

        assertProblem(post("/api/orders/" + orderId + "/cancel", null), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        JsonNode order = order(orderId);
        assertThat(order.get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asInt()).isEqualTo(8);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }
}
