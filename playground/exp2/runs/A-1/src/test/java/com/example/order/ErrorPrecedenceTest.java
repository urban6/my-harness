package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("C3. 오류 우선순위: 400 → 멱등 키(422·409) → 404 → 409(재고 → 쿠폰) → PG(402·503)")
class ErrorPrecedenceTest extends IntegrationTestSupport {

    @Test
    @DisplayName("400이 멱등 키 불일치(422)보다 먼저")
    void validationBeforeIdempotency() {
        long p = createProduct(1_000, 10);
        String user = newUser();
        String key = newKey();
        assertThat(createOrder(user, key, orderBody(null, item(p, 1))).getStatusCode().value()).isEqualTo(201);
        assertProblem(createOrder(user, key, orderBody(null, item(p, 0))), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("멱등 키 불일치(422)가 404보다 먼저")
    void idempotencyBeforeNotFound() {
        long p = createProduct(1_000, 10);
        String user = newUser();
        String key = newKey();
        createOrder(user, key, orderBody(null, item(p, 1)));
        assertProblem(createOrder(user, key, orderBody(null, item(999_999_999L, 1))), 422, "IDEMPOTENCY_KEY_MISMATCH");

        long orderId = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        String payKey = newKey();
        pay(orderId, payKey, "card-ok");
        assertProblem(pay(999_999_999L, payKey, "card-ok"), 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("404가 409보다 먼저 (재고 부족 + 없는 쿠폰 → 404, 없는 상품 + 재고 부족 → 404)")
    void notFoundBeforeConflict() {
        long scarce = createProduct(1_000, 1);
        assertProblem(createOrder(newUser(), newKey(), orderBody("NOSUCHCOUPON", item(scarce, 5))), 404, "COUPON_NOT_FOUND");
        assertProblem(createOrder(newUser(), newKey(), orderBody(null, item(scarce, 5), item(999_999_999L, 1))),
                404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("같은 단계의 409는 재고 → 쿠폰 순")
    void stockBeforeCoupon() {
        long scarce = createProduct(1_000, 1);
        String exhausted = createCoupon("totalQuantity", 1);
        placeOrder(newUser(), exhausted, item(createProduct(1_000, 10), 1));
        assertProblem(createOrder(newUser(), newKey(), orderBody(exhausted, item(scarce, 5))), 409, "INSUFFICIENT_STOCK");

        String notApplicable = createCoupon("minOrderAmount", 1_000_000);
        assertProblem(createOrder(newUser(), newKey(), orderBody(notApplicable, item(scarce, 5))), 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("결제: 400이 404보다, 409가 PG 결과보다 먼저")
    void paymentPrecedence() {
        assertProblem(pay(999_999_999L, newKey(), " "), 400, "VALIDATION_ERROR");

        long p = createProduct(1_000, 10);
        long paid = paidOrder(newUser(), null, item(p, 1)).get("id").asLong();
        assertProblem(pay(paid, newKey(), "error-card"), 409, "INVALID_STATE");
        assertProblem(pay(paid, newKey(), "decline-card"), 409, "INVALID_STATE");
    }
}
