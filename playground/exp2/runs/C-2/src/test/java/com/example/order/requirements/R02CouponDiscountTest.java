package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** R2.4 할인 계산 · R2.5 쿠폰 사용 조건 · R2.6 usedCount 복원 (C1 int 범위 초과 포함). */
class R02CouponDiscountTest extends AbstractIntegrationTest {

    private ApiResponse orderWith(String coupon, long price, int quantity) {
        long productId = newProduct(price, Math.max(quantity, 1));
        return placeOrder(coupon, line(productId, quantity));
    }

    private void assertAmounts(ApiResponse r, long subtotal, long discount, long total) {
        assertThat(r.status()).as("order: %s", r).isEqualTo(201);
        assertThat(r.longValue("subtotal")).as("subtotal").isEqualTo(subtotal);
        assertThat(r.longValue("discount")).as("discount").isEqualTo(discount);
        assertThat(r.longValue("totalPrice")).as("totalPrice").isEqualTo(total);
    }

    // ------------------------------------------------------------------ R2.4

    @Test
    @DisplayName("R2.4 쿠폰이 없으면 discount=0, totalPrice=subtotal=Σ(unitPrice×quantity)이다")
    void r2_4_noCoupon_subtotalIsSumOfLines() {
        long p1 = newProduct(1_200, 10);
        long p2 = newProduct(350, 10);

        ApiResponse r = placeOrder(null, line(p1, 3), line(p2, 4));

        assertAmounts(r, 1_200 * 3 + 350 * 4, 0, 5_000);
        assertThat(r.json().get("items")).hasSize(2);
    }

    @Test
    @DisplayName("R2.4 FIXED는 value만큼 할인한다")
    void r2_4_fixed_discountsByValue() {
        String coupon = newCoupon("FIXED", 3_000, 0, null, 5);

        assertAmounts(orderWith(coupon, 10_000, 1), 10_000, 3_000, 7_000);
    }

    @Test
    @DisplayName("R2.4 RATE는 floor(subtotal × value / 100)이다 (내림)")
    void r2_4_rate_floorsDiscount() {
        String coupon = newCoupon("RATE", 15, 0, null, 5);

        // 9999 × 15 / 100 = 1499.85 -> 1499
        assertAmounts(orderWith(coupon, 3_333, 3), 9_999, 1_499, 8_500);
    }

    @Test
    @DisplayName("R2.4 RATE 계산 결과가 정수로 떨어지지 않는 작은 금액도 내림이다 (1원 × 1% = 0원)")
    void r2_4_rate_smallAmount_floorsToZero() {
        String coupon = newCoupon("RATE", 1, 0, null, 5);

        assertAmounts(orderWith(coupon, 99, 1), 99, 0, 99);
    }

    @Test
    @DisplayName("R2.4 RATE 100%는 subtotal 전액 할인이라 totalPrice가 0이다")
    void r2_4_rate100_makesTotalZero() {
        String coupon = newCoupon("RATE", 100, 0, null, 5);

        assertAmounts(orderWith(coupon, 4_000, 2), 8_000, 8_000, 0);
    }

    @Test
    @DisplayName("R2.4 RATE 할인은 maxDiscountAmount로 상한이 걸린다")
    void r2_4_rate_cappedByMaxDiscountAmount() {
        String coupon = newCoupon("RATE", 50, 0, 20_000L, 5);

        assertAmounts(orderWith(coupon, 100_000, 1), 100_000, 20_000, 80_000);
    }

    @Test
    @DisplayName("R2.4 FIXED 할인도 maxDiscountAmount로 상한이 걸린다")
    void r2_4_fixed_cappedByMaxDiscountAmount() {
        String coupon = newCoupon("FIXED", 8_000, 0, 5_000L, 5);

        assertAmounts(orderWith(coupon, 20_000, 1), 20_000, 5_000, 15_000);
    }

    @Test
    @DisplayName("R2.4 maxDiscountAmount가 계산된 할인보다 크면 영향이 없다")
    void r2_4_maxDiscountAmount_notBinding() {
        String coupon = newCoupon("RATE", 10, 0, 50_000L, 5);

        assertAmounts(orderWith(coupon, 100_000, 1), 100_000, 10_000, 90_000);
    }

    @Test
    @DisplayName("R2.4 FIXED 값이 subtotal보다 크면 마지막에 subtotal로 상한이 걸려 totalPrice는 0이다")
    void r2_4_fixed_cappedBySubtotal() {
        String coupon = newCoupon("FIXED", 5_000, 0, null, 5);

        assertAmounts(orderWith(coupon, 1_000, 1), 1_000, 1_000, 0);
    }

    @Test
    @DisplayName("R2.4 maxDiscountAmount 상한 다음에 subtotal 상한이 적용된다 (min(min(value, max), subtotal))")
    void r2_4_capsAppliedInOrder_maxThenSubtotal() {
        String coupon = newCoupon("FIXED", 5_000, 0, 4_000L, 5);

        assertAmounts(orderWith(coupon, 3_000, 1), 3_000, 3_000, 0);
    }

    @Test
    @DisplayName("R2.4 할인은 여러 항목의 subtotal 합계 기준이다")
    void r2_4_rate_appliesToWholeSubtotal() {
        String coupon = newCoupon("RATE", 10, 0, null, 5);
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(2_500, 10);

        ApiResponse r = placeOrder(coupon, line(p1, 2), line(p2, 4));

        assertAmounts(r, 12_000, 1_200, 10_800);
    }

    @Test
    @DisplayName("C1 subtotal이 int 범위(2^31)를 넘어도 정확하다 (10,000,000원 × 1000개 × 3상품 = 300억)")
    void c1_subtotalBeyondIntRange_isExact() {
        long p1 = newProduct(10_000_000, 1_000);
        long p2 = newProduct(10_000_000, 1_000);
        long p3 = newProduct(10_000_000, 1_000);

        ApiResponse created = placeOrder(null, line(p1, 1_000), line(p2, 1_000), line(p3, 1_000));

        assertAmounts(created, 30_000_000_000L, 0, 30_000_000_000L);
        assertThat(getOrder(created.id()).longValue("subtotal")).isEqualTo(30_000_000_000L);
        assertThat(created.json().get("items").get(0).get("unitPrice").asLong()).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("C1 RATE 할인액이 int 범위를 넘어도 정확하다 (300억의 10% = 30억)")
    void c1_rateDiscountBeyondIntRange_isExact() {
        String coupon = newCoupon("RATE", 10, 0, null, 5);
        long p1 = newProduct(10_000_000, 1_000);
        long p2 = newProduct(10_000_000, 1_000);
        long p3 = newProduct(10_000_000, 1_000);

        ApiResponse created = placeOrder(coupon, line(p1, 1_000), line(p2, 1_000), line(p3, 1_000));

        assertAmounts(created, 30_000_000_000L, 3_000_000_000L, 27_000_000_000L);
    }

    @Test
    @DisplayName("C1 int 범위를 넘는 FIXED value·minOrderAmount·maxDiscountAmount가 왕복·적용된다")
    void c1_couponAmountsBeyondIntRange_roundTripAndApply() {
        String code = uniqueCouponCode();
        ApiResponse created = postCoupon(couponJson(code, "FIXED", 5_000_000_000L, 3_000_000_000L, 4_000_000_000L, 5));
        assertThat(created.status()).isEqualTo(201);
        assertThat(getCoupon(code).longValue("value")).isEqualTo(5_000_000_000L);
        assertThat(getCoupon(code).longValue("minOrderAmount")).isEqualTo(3_000_000_000L);
        assertThat(getCoupon(code).longValue("maxDiscountAmount")).isEqualTo(4_000_000_000L);
        long p1 = newProduct(10_000_000, 1_000);
        long p2 = newProduct(10_000_000, 1_000);
        long p3 = newProduct(10_000_000, 1_000);

        ApiResponse order = placeOrder(code, line(p1, 1_000), line(p2, 1_000), line(p3, 1_000));

        assertAmounts(order, 30_000_000_000L, 4_000_000_000L, 26_000_000_000L);
    }

    @Test
    @DisplayName("C1 int 범위를 넘는 totalPrice는 PG 결제 요청 amount로 그대로 전달된다")
    void c1_totalPriceBeyondIntRange_isSentToGatewayAsIs() {
        String coupon = newCoupon("RATE", 10, 0, null, 5);
        long p1 = newProduct(10_000_000, 1_000);
        long p2 = newProduct(10_000_000, 1_000);
        long p3 = newProduct(10_000_000, 1_000);
        ApiResponse order = placeOrder(coupon, line(p1, 1_000), line(p2, 1_000), line(p3, 1_000));

        ApiResponse paid = pay(order.id());

        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.longValue("totalPrice")).isEqualTo(27_000_000_000L);
        assertThat(PG.paymentCalls()).hasSize(1);
        assertThat(PG.paymentCalls().get(0).amount()).isEqualTo(27_000_000_000L);
    }

    // ------------------------------------------------------------------ R2.5

    @Test
    @DisplayName("R2.5 validFrom 이전에는 409 COUPON_NOT_APPLICABLE이고 예약·사용이 남지 않는다")
    void r2_5_beforeValidFrom_notApplicable() {
        String code = uniqueCouponCode();
        postCoupon(couponJson(code, "FIXED", 100, 0, null, 5, Instant.now().plus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofHours(2))));
        long productId = newProduct(1_000, 5);

        ApiResponse r = placeOrder(code, line(productId, 1));

        assertProblem(r, 409, "COUPON_NOT_APPLICABLE");
        assertThat(usedCount(code)).isZero();
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R2.5 validUntil 이후에는 409 COUPON_NOT_APPLICABLE이다")
    void r2_5_afterValidUntil_notApplicable() {
        String code = uniqueCouponCode();
        postCoupon(couponJson(code, "FIXED", 100, 0, null, 5, Instant.now().minus(Duration.ofHours(2)),
                Instant.now().minus(Duration.ofHours(1))));
        long productId = newProduct(1_000, 5);

        ApiResponse r = placeOrder(code, line(productId, 1));

        assertProblem(r, 409, "COUPON_NOT_APPLICABLE");
        assertThat(usedCount(code)).isZero();
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R2.5 validFrom에 도달하면 사용할 수 있다 (이전 409 -> 이후 201)")
    void r2_5_validFromReached_becomesApplicable() throws Exception {
        String code = uniqueCouponCode();
        Instant from = Instant.now().plusSeconds(3);
        postCoupon(couponJson(code, "FIXED", 100, 0, null, 5, from, from.plus(Duration.ofHours(1))));
        long productId = newProduct(1_000, 5);

        assertThat(placeOrder(code, line(productId, 1)).code()).isEqualTo("COUPON_NOT_APPLICABLE");
        sleepUntil(from.plusMillis(300));

        assertThat(placeOrder(code, line(productId, 1)).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 validUntil에 도달하면 사용할 수 없다 (이전 201 -> 이후 409)")
    void r2_5_validUntilReached_becomesNotApplicable() throws Exception {
        String code = uniqueCouponCode();
        Instant until = Instant.now().plusSeconds(5);
        postCoupon(couponJson(code, "FIXED", 100, 0, null, 5, Instant.now().minus(Duration.ofHours(1)), until));
        long productId = newProduct(1_000, 5);

        assertThat(placeOrder(code, line(productId, 1)).status()).isEqualTo(201);
        sleepUntil(until.plusMillis(300));

        assertProblem(placeOrder(code, line(productId, 1)), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 subtotal < minOrderAmount이면 409 COUPON_NOT_APPLICABLE이다 (1원 부족)")
    void r2_5_belowMinOrderAmount_notApplicable() {
        String code = newCoupon("FIXED", 1_000, 10_000, null, 5);
        long productId = newProduct(9_999, 5);

        ApiResponse r = placeOrder(code, line(productId, 1));

        assertProblem(r, 409, "COUPON_NOT_APPLICABLE");
        assertThat(usedCount(code)).isZero();
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R2.5 subtotal == minOrderAmount이면 사용할 수 있다 (이상)")
    void r2_5_equalToMinOrderAmount_applicable() {
        String code = newCoupon("FIXED", 1_000, 10_000, null, 5);

        assertAmounts(orderWith(code, 10_000, 1), 10_000, 1_000, 9_000);
    }

    @Test
    @DisplayName("R2.5 minOrderAmount는 할인 전 subtotal과 비교한다")
    void r2_5_minOrderAmount_comparesPreDiscountSubtotal() {
        String code = newCoupon("FIXED", 9_000, 10_000, null, 5);

        assertAmounts(orderWith(code, 10_000, 1), 10_000, 9_000, 1_000);
    }

    @ParameterizedTest(name = "R2.5 같은 사용자의 {0} 주문이 있으면 다시 쓸 수 없다")
    @ValueSource(strings = {"PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED"})
    @DisplayName("R2.5 같은 사용자가 쿠폰을 사용 중인 주문이 있으면 409 COUPON_NOT_APPLICABLE이다")
    void r2_5_sameUserInUse_notApplicable(String status) {
        String code = newCoupon("FIXED", 100, 0, null, 50);
        long productId = newProduct(1_000, 10);
        String user = uniqueUser();
        ApiResponse first = postOrder(user, uniqueKey(), orderJson(code, line(productId, 1)));
        assertThat(first.status()).isEqualTo(201);
        driveOrderTo(first.id(), status);

        ApiResponse second = postOrder(user, uniqueKey(), orderJson(code, line(productId, 1)));

        assertProblem(second, 409, "COUPON_NOT_APPLICABLE");
        assertThat(usedCount(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("R2.5 다른 사용자는 같은 쿠폰을 쓸 수 있고, 같은 사용자도 다른 쿠폰은 쓸 수 있다")
    void r2_5_sameUserRestriction_isPerUserAndPerCoupon() {
        String c1 = newCoupon("FIXED", 100, 0, null, 50);
        String c2 = newCoupon("FIXED", 100, 0, null, 50);
        long productId = newProduct(1_000, 10);
        String user = uniqueUser();
        assertThat(postOrder(user, uniqueKey(), orderJson(c1, line(productId, 1))).status()).isEqualTo(201);

        assertThat(postOrder(uniqueUser(), uniqueKey(), orderJson(c1, line(productId, 1))).status()).isEqualTo(201);
        assertThat(postOrder(user, uniqueKey(), orderJson(c2, line(productId, 1))).status()).isEqualTo(201);
        assertThat(usedCount(c1)).isEqualTo(2);
    }

    @Test
    @DisplayName("R2.5 usedCount == totalQuantity이면 409 COUPON_EXHAUSTED이고 예약이 남지 않는다")
    void r2_5_exhausted_returnsCouponExhausted() {
        String code = newCoupon("FIXED", 100, 0, null, 1);
        long productId = newProduct(1_000, 10);
        assertThat(placeOrder(code, line(productId, 1)).status()).isEqualTo(201);

        ApiResponse r = placeOrder(code, line(productId, 1));

        assertProblem(r, 409, "COUPON_EXHAUSTED");
        assertThat(usedCount(code)).isEqualTo(1);
        assertThat(reserved(productId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ R2.6

    @ParameterizedTest(name = "R2.6 주문이 {0}가 되면 usedCount가 1 줄고 같은 사용자가 다시 쓸 수 있다")
    @ValueSource(strings = {"CANCELLED", "PAYMENT_FAILED", "REFUNDED"})
    @DisplayName("R2.6 CANCELLED·PAYMENT_FAILED·REFUNDED가 되면 쿠폰 사용이 복원된다")
    void r2_6_terminalStates_restoreCouponUse(String status) {
        String code = newCoupon("FIXED", 100, 0, null, 5);
        long productId = newProduct(1_000, 10);
        String user = uniqueUser();
        ApiResponse first = postOrder(user, uniqueKey(), orderJson(code, line(productId, 1)));
        assertThat(usedCount(code)).isEqualTo(1);

        driveOrderTo(first.id(), status);

        assertThat(usedCount(code)).isZero();
        ApiResponse again = postOrder(user, uniqueKey(), orderJson(code, line(productId, 1)));
        assertThat(again.status()).isEqualTo(201);
        assertThat(usedCount(code)).isEqualTo(1);
    }

    @ParameterizedTest(name = "R2.6 주문이 {0} 상태이면 usedCount는 그대로 1이다")
    @ValueSource(strings = {"PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED"})
    @DisplayName("R2.6 PENDING_PAYMENT·PAID·SHIPPED·DELIVERED는 쿠폰을 사용 중인 주문으로 센다")
    void r2_6_activeStates_keepCouponUse(String status) {
        String code = newCoupon("FIXED", 100, 0, null, 5);
        long productId = newProduct(1_000, 10);
        ApiResponse order = placeOrder(code, line(productId, 1));

        driveOrderTo(order.id(), status);

        assertThat(usedCount(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("R2.6 소진된 쿠폰도 사용 중이던 주문이 취소되면 다른 사용자가 쓸 수 있다")
    void r2_6_exhaustedCoupon_becomesAvailableAfterRestore() {
        String code = newCoupon("FIXED", 100, 0, null, 1);
        long productId = newProduct(1_000, 10);
        ApiResponse first = placeOrder(code, line(productId, 1));
        assertThat(placeOrder(code, line(productId, 1)).code()).isEqualTo("COUPON_EXHAUSTED");

        cancel(first.id());

        assertThat(placeOrder(code, line(productId, 1)).status()).isEqualTo(201);
        assertThat(usedCount(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("R2.6 복원은 주문당 한 번만 일어난다 (취소 후 재취소 409로 usedCount가 음수가 되지 않는다)")
    void r2_6_restoreHappensOnce() {
        String code = newCoupon("FIXED", 100, 0, null, 5);
        long productId = newProduct(1_000, 10);
        ApiResponse a = placeOrder(code, line(productId, 1));
        placeOrder(code, line(productId, 1));
        assertThat(usedCount(code)).isEqualTo(2);

        cancel(a.id());
        assertThat(cancel(a.id()).status()).isEqualTo(409);

        assertThat(usedCount(code)).isEqualTo(1);
    }

    private static void sleepUntil(Instant instant) throws InterruptedException {
        long ms = Duration.between(Instant.now(), instant).toMillis();
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }
}
