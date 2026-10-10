package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.IntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 짧은 TTL(PT2S) 컨텍스트 예시: IntegrationTestSupport를 직접 상속해 프로퍼티 조합을 바꾼다. */
class OrderExpirationSmokeTest extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("payment.gateway.url", PG::baseUrl);
        registry.add("order.payment-ttl", () -> "PT2S");
    }

    @Test
    @DisplayName("R6.1 R6.2 expiresAt + 2초 안에 EXPIRED, 예약·쿠폰 복원")
    void pendingOrder_expiresWithin2Seconds_andRestoresReservationAndCoupon() throws Exception {
        long productId = newProduct(1000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 3);
        ApiResponse created = placeOrder(coupon, line(productId, 4));
        assertThat(created.status()).isEqualTo(201);
        Instant expiresAt = OffsetDateTime.parse(created.text("expiresAt")).toInstant();
        assertThat(Duration.between(OffsetDateTime.parse(created.text("createdAt")).toInstant(), expiresAt))
                .isEqualTo(Duration.ofSeconds(2));

        long sleepMs = Duration.between(Instant.now(), expiresAt.plusSeconds(2)).toMillis();
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }

        assertThat(getOrder(created.id()).text("status")).isEqualTo("EXPIRED");
        assertThat(getProduct(productId).json().get("reserved").asInt()).isZero();
        assertThat(getCoupon(coupon).longValue("usedCount")).isZero();
    }

    @Test
    @DisplayName("R5.2 만료 후 결제는 409 INVALID_STATE")
    void pay_afterExpiry_returns409InvalidState() throws Exception {
        long orderId = placeOrderOk(newProduct(1000, 10), 1).id();

        awaitOrderStatus(orderId, "EXPIRED", Duration.ofSeconds(6));
        ApiResponse r = pay(orderId);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(PG.paymentCallCount()).isZero();
    }
}
