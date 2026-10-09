package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** C3: 400 -> 멱등 키(422/409) -> 404 -> 409(재고 -> 쿠폰) -> PG 결과(402/503). */
@DisplayName("C3 오류 우선순위")
class C3PriorityTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("C3 400 > 404: 수량 검증 오류 + 없는 상품 -> 400")
    void validation_beats_productNotFound() {
        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), null, Long.MAX_VALUE - 1, 0);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400 > 404: 헤더 누락 + 없는 상품/없는 쿠폰 -> 400")
    void missingHeader_beats_notFound() {
        ApiResponse r = post("/api/orders", orderBody("NOSUCHCOUPON", Long.MAX_VALUE - 1, 1), headers());

        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("C3 400 > 404: 결제 cardToken 공백 + 없는 주문 -> 400")
    void pay_validation_beats_orderNotFound() {
        ApiResponse r = post("/api/orders/" + (Long.MAX_VALUE - 1) + "/pay", "{\"cardToken\":\" \"}",
                headers("Idempotency-Key", uniqueKey()));

        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("C3 400 > 404: 중복 productId + 없는 상품 -> 400")
    void duplicateProduct_beats_notFound() {
        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), null, Long.MAX_VALUE - 1, 1, Long.MAX_VALUE - 1, 1);

        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("C3 422 > 404: 다른 요청에 쓰인 키 + 없는 쿠폰 -> 422")
    void mismatch_beats_notFound() {
        long p = newProduct(1_000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        createOrder(user, key, null, p, 1);

        ApiResponse r = createOrder(user, key, "NOSUCH" + System.nanoTime(), p, 1);

        assertThat(r.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("C3 404 > 409: 재고 부족 상품 + 없는 상품 -> 404 PRODUCT_NOT_FOUND")
    void productNotFound_beats_insufficientStock() {
        long low = newProduct(1_000, 1);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), null, low, 5, Long.MAX_VALUE - 1, 1);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 404 > 409: 재고 부족 + 없는 쿠폰 -> 404 COUPON_NOT_FOUND")
    void couponNotFound_beats_insufficientStock() {
        long low = newProduct(1_000, 1);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), "NOSUCH" + System.nanoTime(), low, 5);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 404 > 503: 없는 주문 결제는 PG 가 장애여도 404, PG 미호출")
    void orderNotFound_beats_pgFailure() {
        stubPgPaymentStatus(500);

        ApiResponse r = pay(Long.MAX_VALUE - 1, uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(404);
        assertThat(pgPaymentRequestCount()).isZero();
    }

    @Test
    @DisplayName("C3 재고 409 > 쿠폰 409: 재고 부족 + 쿠폰 소진 -> INSUFFICIENT_STOCK")
    void insufficientStock_beats_couponExhausted() {
        String coupon = newCoupon("FIXED", 100, null, null, 1);
        long p = newProduct(1_000, 3);
        newOrder(uniqueUser(), coupon, p, 1);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), coupon, p, 5);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 재고 409 > 쿠폰 409: 재고 부족 + 쿠폰 조건 미달 -> INSUFFICIENT_STOCK")
    void insufficientStock_beats_couponNotApplicable() {
        String coupon = newCoupon("FIXED", 100, 10_000_000L, null, 5);
        long p = newProduct(1_000, 3);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), coupon, p, 5);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 409 > PG 결과: 이미 PAID 인 주문 결제는 PG 가 장애/거절이어도 409, PG 미호출")
    void invalidState_beats_pgResult() {
        long p = newProduct(1_000, 5);
        ApiResponse paid = newPaidOrder(uniqueUser(), null, p, 1);
        WIREMOCK.resetAll();
        stubPgPaymentStatus(500);

        ApiResponse r = pay(paid.id(), uniqueKey(), "tok");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(pgPaymentRequestCount()).isZero();
    }

    @Test
    @DisplayName("C3 409 > PG 결과: SHIPPED 주문 취소는 환불 PG 가 장애여도 409, 환불 미호출")
    void cancelShipped_409_beats_refundFailure() {
        long p = newProduct(1_000, 5);
        ApiResponse paid = newPaidOrder(uniqueUser(), null, p, 1);
        ship(paid.id());
        stubPgRefundStatus(500);

        ApiResponse r = cancel(paid.id());

        assertThat(r.status()).isEqualTo(409);
        assertThat(pgRefundRequestCount()).isZero();
    }
}
