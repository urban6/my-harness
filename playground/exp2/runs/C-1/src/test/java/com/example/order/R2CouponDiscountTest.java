package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("R2.4 할인 계산 / C1 int 범위를 넘는 금액")
class R2CouponDiscountTest extends IntegrationTestBase {

    /** type, value, maxDiscount(null 가능), price, qty, 기대 subtotal, 기대 discount */
    static Stream<Arguments> discountCases() {
        return Stream.of(
                Arguments.of("FIXED 는 value 만큼", "FIXED", 3000L, null, 10000L, 1, 10000L, 3000L),
                Arguments.of("FIXED 가 subtotal 보다 크면 subtotal 로 상한", "FIXED", 20000L, null, 10000L, 1, 10000L, 10000L),
                Arguments.of("FIXED == subtotal 이면 전액 할인", "FIXED", 10000L, null, 5000L, 2, 10000L, 10000L),
                Arguments.of("FIXED 는 maxDiscountAmount 로 상한", "FIXED", 5000L, 2000L, 10000L, 1, 10000L, 2000L),
                Arguments.of("RATE 는 floor(subtotal*value/100)", "RATE", 10L, null, 999L, 1, 999L, 99L),
                Arguments.of("RATE 33% of 1000 = 330", "RATE", 33L, null, 1000L, 1, 1000L, 330L),
                Arguments.of("RATE 가 1 미만으로 떨어지면 0", "RATE", 1L, null, 99L, 1, 99L, 0L),
                Arguments.of("RATE 7% of 1999 = floor(139.93) = 139", "RATE", 7L, null, 1999L, 1, 1999L, 139L),
                Arguments.of("RATE 는 maxDiscountAmount 로 상한", "RATE", 50L, 3000L, 100000L, 1, 100000L, 3000L),
                Arguments.of("RATE 가 max 보다 작으면 RATE 값 그대로", "RATE", 50L, 100000L, 100000L, 1, 100000L, 50000L),
                Arguments.of("RATE 100% 는 전액 할인", "RATE", 100L, null, 12345L, 1, 12345L, 12345L),
                Arguments.of("max 가 subtotal 보다 커도 subtotal 이 최종 상한", "FIXED", 5000L, 9000L, 3000L, 1, 3000L, 3000L),
                Arguments.of("수량이 곱해진 subtotal 기준", "RATE", 10L, null, 1500L, 4, 6000L, 600L));
    }

    @ParameterizedTest(name = "R2.4 {0}")
    @MethodSource("discountCases")
    void r2_4_discountCalculation(String label, String type, long value, Long max, long price, int qty,
                                  long expectedSubtotal, long expectedDiscount) {
        long productId = createProduct("상품", price, 1000).get("id").asLong();
        Map<String, Object> coupon = couponBody("DISC0001", type, value);
        if (max != null) {
            coupon.put("maxDiscountAmount", max);
        }
        createCoupon(coupon);

        JsonNode order = orderOk("user-1", "DISC0001", productId, qty);

        assertThat(order.get("subtotal").asLong()).isEqualTo(expectedSubtotal);
        assertThat(order.get("discount").asLong()).isEqualTo(expectedDiscount);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(expectedSubtotal - expectedDiscount);
        assertThat(order.get("couponCode").asText()).isEqualTo("DISC0001");
        JsonNode fetched = getOrder(order.get("id").asLong());
        assertThat(fetched.get("discount").asLong()).isEqualTo(expectedDiscount);
        assertThat(fetched.get("totalPrice").asLong()).isEqualTo(expectedSubtotal - expectedDiscount);
    }

    @Test
    @DisplayName("R2.4 쿠폰이 없으면 discount=0, totalPrice=subtotal, couponCode=null")
    void r2_4_noCoupon() {
        long p1 = createProduct("a", 1500, 10).get("id").asLong();
        long p2 = createProduct("b", 700, 10).get("id").asLong();

        JsonNode order = orderOk("user-1", null, p1, 2, p2, 3);

        assertThat(order.get("subtotal").asLong()).isEqualTo(1500 * 2 + 700 * 3);
        assertThat(order.get("discount").asLong()).isZero();
        assertThat(order.get("totalPrice").asLong()).isEqualTo(5100);
        assertThat(order.get("couponCode").isNull()).isTrue();
    }

    @Test
    @DisplayName("R2.4 여러 항목의 subtotal = Σ(unitPrice × quantity) 에 RATE 를 적용")
    void r2_4_multiItemSubtotal() {
        long p1 = createProduct("a", 1500, 10).get("id").asLong();
        long p2 = createProduct("b", 700, 10).get("id").asLong();
        createCoupon("MULTI001", "RATE", 10);

        JsonNode order = orderOk("user-1", "MULTI001", p1, 2, p2, 3);

        assertThat(order.get("subtotal").asLong()).isEqualTo(5100);
        assertThat(order.get("discount").asLong()).isEqualTo(510);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(4590);
    }

    @Test
    @DisplayName("C1 subtotal 이 2^31 을 넘는 주문(10,000,000 × 1000 × 3개 상품)도 정확히 계산된다")
    void c1_subtotalBeyondIntRange() {
        long p1 = createProduct("a", 10_000_000, 1000).get("id").asLong();
        long p2 = createProduct("b", 10_000_000, 1000).get("id").asLong();
        long p3 = createProduct("c", 10_000_000, 1000).get("id").asLong();

        JsonNode order = orderOk("user-1", null, p1, 1000, p2, 1000, p3, 1000);

        assertThat(order.get("subtotal").asLong()).isEqualTo(30_000_000_000L);
        assertThat(order.get("discount").asLong()).isZero();
        assertThat(order.get("totalPrice").asLong()).isEqualTo(30_000_000_000L);
        assertThat(getOrder(order.get("id").asLong()).get("totalPrice").asLong()).isEqualTo(30_000_000_000L);
    }

    @Test
    @DisplayName("C1 단일 항목만으로 int 를 넘는 금액(10,000,000 × 1000 = 10^10)")
    void c1_singleLineBeyondIntRange() {
        long p = createProduct("a", 10_000_000, 1000).get("id").asLong();

        JsonNode order = orderOk("user-1", null, p, 1000);

        assertThat(order.get("subtotal").asLong()).isEqualTo(10_000_000_000L);
    }

    @Test
    @DisplayName("C1 RATE 할인이 int 범위를 넘어도 정확하다 (3×10^10 의 15% = 4.5×10^9)")
    void c1_rateDiscountBeyondIntRange() {
        long p1 = createProduct("a", 10_000_000, 1000).get("id").asLong();
        long p2 = createProduct("b", 10_000_000, 1000).get("id").asLong();
        long p3 = createProduct("c", 10_000_000, 1000).get("id").asLong();
        createCoupon("BIGRATE1", "RATE", 15);

        JsonNode order = orderOk("user-1", "BIGRATE1", p1, 1000, p2, 1000, p3, 1000);

        assertThat(order.get("subtotal").asLong()).isEqualTo(30_000_000_000L);
        assertThat(order.get("discount").asLong()).isEqualTo(4_500_000_000L);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(25_500_000_000L);
    }

    @Test
    @DisplayName("C1 최대 주문(20항목 × 1000 × 10,000,000 = 2×10^11)과 RATE 99% 도 정확하다")
    void c1_maximumOrder() {
        long[] pq = new long[40];
        for (int i = 0; i < 20; i++) {
            pq[2 * i] = createProduct("p" + i, 10_000_000, 1000).get("id").asLong();
            pq[2 * i + 1] = 1000;
        }
        createCoupon("BIGRATE2", "RATE", 99);

        JsonNode order = orderOk("user-1", "BIGRATE2", pq);

        assertThat(order.get("subtotal").asLong()).isEqualTo(200_000_000_000L);
        assertThat(order.get("discount").asLong()).isEqualTo(198_000_000_000L);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(2_000_000_000L);
    }

    @Test
    @DisplayName("C1 int 범위를 넘는 FIXED value / maxDiscountAmount / minOrderAmount 도 처리한다")
    void c1_bigCouponAmounts() {
        long p1 = createProduct("a", 10_000_000, 1000).get("id").asLong();
        long p2 = createProduct("b", 10_000_000, 1000).get("id").asLong();
        long p3 = createProduct("c", 10_000_000, 1000).get("id").asLong();
        Map<String, Object> coupon = couponBody("BIGFIX01", "FIXED", 9_000_000_000L);
        coupon.put("maxDiscountAmount", 4_000_000_000L);
        coupon.put("minOrderAmount", 30_000_000_000L);
        JsonNode created = createCoupon(coupon);
        assertThat(created.get("value").asLong()).isEqualTo(9_000_000_000L);
        assertThat(created.get("maxDiscountAmount").asLong()).isEqualTo(4_000_000_000L);
        assertThat(created.get("minOrderAmount").asLong()).isEqualTo(30_000_000_000L);

        JsonNode order = orderOk("user-1", "BIGFIX01", p1, 1000, p2, 1000, p3, 1000);

        assertThat(order.get("discount").asLong()).isEqualTo(4_000_000_000L);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(26_000_000_000L);
    }

    @Test
    @DisplayName("C1/R5.3 int 범위를 넘는 totalPrice 가 PG amount 로 그대로 전달되고 결제는 승인된다")
    void c1_payBigAmount() {
        long p1 = createProduct("a", 10_000_000, 1000).get("id").asLong();
        long p2 = createProduct("b", 10_000_000, 1000).get("id").asLong();
        long p3 = createProduct("c", 10_000_000, 1000).get("id").asLong();
        long orderId = orderOk("user-1", null, p1, 1000, p2, 1000, p3, 1000).get("id").asLong();

        JsonNode paid = payOk(orderId);

        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("totalPrice").asLong()).isEqualTo(30_000_000_000L);
        assertThat(PG.lastPayRequest().body()).contains("\"amount\":30000000000");
    }

    @Test
    @DisplayName("R2.4/R5.3 PG 에는 할인 후 totalPrice 가 amount 로 전달된다")
    void r2_4_pgReceivesDiscountedTotal() {
        long p = createProduct("a", 10000, 10).get("id").asLong();
        createCoupon("PGAMT001", "FIXED", 3000);
        long orderId = orderOk("user-1", "PGAMT001", p, 1).get("id").asLong();

        payOk(orderId);

        assertThat(PG.lastPayRequest().body()).contains("\"amount\":7000");
    }

    @Test
    @DisplayName("R2.4/R5.7 totalPrice 가 0 이면 PG 를 호출하지 않고 바로 PAID")
    void r2_4_zeroTotalSkipsPg() {
        long p = createProduct("a", 10000, 10).get("id").asLong();
        createCoupon("FREE0001", "RATE", 100);
        JsonNode order = orderOk("user-1", "FREE0001", p, 2);
        assertThat(order.get("totalPrice").asLong()).isZero();

        JsonNode paid = payOk(order.get("id").asLong());

        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("paidAt").isNull()).isFalse();
        assertThat(PG.payCallCount()).isZero();
        JsonNode product = getProduct(p);
        assertThat(product.get("stock").asInt()).isEqualTo(8);
        assertThat(product.get("reserved").asInt()).isZero();
    }
}
