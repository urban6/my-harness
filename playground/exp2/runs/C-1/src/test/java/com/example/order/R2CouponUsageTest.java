package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R2.5/R2.6 쿠폰 사용 조건과 복원")
class R2CouponUsageTest extends IntegrationTestBase {

    private long product(long price, int stock) {
        return createProduct("상품", price, stock).get("id").asLong();
    }

    private int usedCount(String code) {
        return getCoupon(code).get("usedCount").asInt();
    }

    // ---------------- R2.5 적용 조건 ----------------

    @Test
    @DisplayName("R2.5 validFrom 이 미래이면 409 COUPON_NOT_APPLICABLE, 예약·사용 수 불변")
    void r2_5_notYetValid() {
        long p = product(10000, 10);
        Map<String, Object> c = couponBody("FUTURE01", "FIXED", 1000);
        c.put("validFrom", Instant.now().plusSeconds(3600).toString());
        c.put("validUntil", Instant.now().plusSeconds(7200).toString());
        createCoupon(c);

        ResponseEntity<JsonNode> res = order("u1", "FUTURE01", p, 1);

        assertProblem(res, 409, "COUPON_NOT_APPLICABLE");
        assertThat(getProduct(p).get("reserved").asInt()).isZero();
        assertThat(usedCount("FUTURE01")).isZero();
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("R2.5 validUntil 이 지났으면 409 COUPON_NOT_APPLICABLE")
    void r2_5_expiredCoupon() {
        long p = product(10000, 10);
        Map<String, Object> c = couponBody("PAST0001", "FIXED", 1000);
        c.put("validFrom", Instant.now().minusSeconds(7200).toString());
        c.put("validUntil", Instant.now().minusSeconds(3600).toString());
        createCoupon(c);

        assertProblem(order("u1", "PAST0001", p, 1), 409, "COUPON_NOT_APPLICABLE");
        assertThat(usedCount("PAST0001")).isZero();
    }

    @Test
    @DisplayName("R2.5 subtotal 이 minOrderAmount 에 정확히 같으면 적용된다")
    void r2_5_subtotalEqualsMinOrderAmount() {
        long p = product(5000, 10);
        Map<String, Object> c = couponBody("MINEQ001", "FIXED", 1000);
        c.put("minOrderAmount", 10000);
        createCoupon(c);

        JsonNode o = orderOk("u1", "MINEQ001", p, 2);

        assertThat(o.get("discount").asLong()).isEqualTo(1000);
    }

    @Test
    @DisplayName("R2.5 subtotal 이 minOrderAmount 보다 1 작으면 409 COUPON_NOT_APPLICABLE")
    void r2_5_subtotalBelowMinOrderAmount() {
        long p = product(9999, 10);
        Map<String, Object> c = couponBody("MINLT001", "FIXED", 1000);
        c.put("minOrderAmount", 10000);
        createCoupon(c);

        assertProblem(order("u1", "MINLT001", p, 1), 409, "COUPON_NOT_APPLICABLE");
        assertThat(usedCount("MINLT001")).isZero();
        assertThat(getProduct(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R2.5 같은 사용자가 이미 사용 중이면 409 COUPON_NOT_APPLICABLE, 다른 사용자는 사용 가능")
    void r2_5_sameUserAlreadyUsing() {
        long p = product(10000, 10);
        createCoupon("ONCE0001", "FIXED", 1000);
        orderOk("u1", "ONCE0001", p, 1);

        ResponseEntity<JsonNode> again = order("u1", "ONCE0001", p, 1);
        ResponseEntity<JsonNode> other = order("u2", "ONCE0001", p, 1);

        assertProblem(again, 409, "COUPON_NOT_APPLICABLE");
        assertThat(other.getStatusCode().value()).isEqualTo(201);
        assertThat(usedCount("ONCE0001")).isEqualTo(2);
        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("R2.5 같은 사용자의 이전 주문이 PAID 여도 계속 사용 중으로 본다")
    void r2_5_stillBlockedWhenPrevOrderPaid() {
        long p = product(10000, 10);
        createCoupon("PAIDBLK1", "FIXED", 1000);
        payOk(orderOk("u1", "PAIDBLK1", p, 1).get("id").asLong());

        assertProblem(order("u1", "PAIDBLK1", p, 1), 409, "COUPON_NOT_APPLICABLE");
        assertThat(usedCount("PAIDBLK1")).isEqualTo(1);
    }

    @Test
    @DisplayName("R2.5/R2.6 SHIPPED·DELIVERED 주문도 사용 중이므로 usedCount 유지, 재사용 불가")
    void r2_5_stillBlockedWhenShippedOrDelivered() {
        long p = product(10000, 10);
        createCoupon("SHIPBLK1", "FIXED", 1000);
        long id = orderOk("u1", "SHIPBLK1", p, 1).get("id").asLong();
        payOk(id);

        assertThat(ship(id).getStatusCode().value()).isEqualTo(200);
        assertThat(usedCount("SHIPBLK1")).isEqualTo(1);
        assertProblem(order("u1", "SHIPBLK1", p, 1), 409, "COUPON_NOT_APPLICABLE");

        assertThat(deliver(id).getStatusCode().value()).isEqualTo(200);
        assertThat(usedCount("SHIPBLK1")).isEqualTo(1);
        assertProblem(order("u1", "SHIPBLK1", p, 1), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 usedCount == totalQuantity 이면 다른 사용자도 409 COUPON_EXHAUSTED")
    void r2_5_exhausted() {
        long p = product(10000, 10);
        Map<String, Object> c = couponBody("LIMIT002", "FIXED", 1000);
        c.put("totalQuantity", 2);
        createCoupon(c);
        orderOk("u1", "LIMIT002", p, 1);
        orderOk("u2", "LIMIT002", p, 1);

        ResponseEntity<JsonNode> res = order("u3", "LIMIT002", p, 1);

        assertProblem(res, 409, "COUPON_EXHAUSTED");
        assertThat(usedCount("LIMIT002")).isEqualTo(2);
        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(2);
    }

    // ---------------- R2.6 복원 ----------------

    @Test
    @DisplayName("R2.6 CANCELLED: usedCount 1 감소, 같은 사용자가 다시 사용 가능")
    void r2_6_restoreOnCancel() {
        long p = product(10000, 10);
        createCoupon("RESTORE1", "FIXED", 1000);
        long id = orderOk("u1", "RESTORE1", p, 1).get("id").asLong();
        assertThat(usedCount("RESTORE1")).isEqualTo(1);

        assertThat(cancel(id).getStatusCode().value()).isEqualTo(200);

        assertThat(usedCount("RESTORE1")).isZero();
        assertThat(order("u1", "RESTORE1", p, 1).getStatusCode().value()).isEqualTo(201);
        assertThat(usedCount("RESTORE1")).isEqualTo(1);
    }

    @Test
    @DisplayName("R2.6 PAYMENT_FAILED(결제 거절): usedCount 복원, 같은 사용자 재사용 가능")
    void r2_6_restoreOnPaymentFailed() {
        long p = product(10000, 10);
        createCoupon("RESTORE2", "FIXED", 1000);
        long id = orderOk("u1", "RESTORE2", p, 1).get("id").asLong();
        PG.decline();

        assertProblem(payOrder(id, key(), "tok"), 402, "PAYMENT_DECLINED");

        assertThat(statusOf(id)).isEqualTo("PAYMENT_FAILED");
        assertThat(usedCount("RESTORE2")).isZero();
        assertThat(order("u1", "RESTORE2", p, 1).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 REFUNDED(PAID 취소): usedCount 복원, 같은 사용자 재사용 가능")
    void r2_6_restoreOnRefund() {
        long p = product(10000, 10);
        createCoupon("RESTORE3", "FIXED", 1000);
        long id = orderOk("u1", "RESTORE3", p, 1).get("id").asLong();
        payOk(id);
        assertThat(usedCount("RESTORE3")).isEqualTo(1);

        assertThat(cancel(id).getStatusCode().value()).isEqualTo(200);

        assertThat(statusOf(id)).isEqualTo("REFUNDED");
        assertThat(usedCount("RESTORE3")).isZero();
        assertThat(order("u1", "RESTORE3", p, 1).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 소진된 쿠폰도 사용 주문이 취소되면 다른 사용자가 다시 쓸 수 있다")
    void r2_6_exhaustedCouponFreedByCancel() {
        long p = product(10000, 10);
        Map<String, Object> c = couponBody("LIMIT001", "FIXED", 1000);
        c.put("totalQuantity", 1);
        createCoupon(c);
        long id = orderOk("u1", "LIMIT001", p, 1).get("id").asLong();
        assertProblem(order("u2", "LIMIT001", p, 1), 409, "COUPON_EXHAUSTED");

        cancel(id);

        assertThat(order("u2", "LIMIT001", p, 1).getStatusCode().value()).isEqualTo(201);
        assertThat(usedCount("LIMIT001")).isEqualTo(1);
    }

    @Test
    @DisplayName("R2.6 쿠폰 없는 주문의 취소는 어떤 쿠폰의 usedCount 도 바꾸지 않는다")
    void r2_6_cancelWithoutCouponLeavesOtherCoupons() {
        long p = product(10000, 10);
        createCoupon("OTHER001", "FIXED", 1000);
        orderOk("u2", "OTHER001", p, 1);
        long id = orderOk("u1", null, p, 1).get("id").asLong();

        cancel(id);

        assertThat(usedCount("OTHER001")).isEqualTo(1);
    }
}
