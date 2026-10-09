package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** 스모크: 구현이 실제로 도는지만 확인한다. 요구사항 전량 커버는 Phase 3. */
class OrderPaymentSmokeTest extends AbstractIntegrationTest {

    @Test
    void happyPath_product_order_pay_get() {
        long productId = newProduct(1_000, 10);
        String user = uniqueUser();

        ApiResponse created = createOrder(user, uniqueKey(), null, productId, 2);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("Location")).isEqualTo("/api/orders/" + created.id());
        assertThat(created.json("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(created.json("subtotal").asLong()).isEqualTo(2_000);
        assertThat(created.json("paidAt").isNull()).isTrue();
        assertThat(getProduct(productId).json("reserved").asInt()).isEqualTo(2);

        stubPgPayment("APPROVED", "pay-smoke");
        String payKey = uniqueKey();
        ApiResponse paid = pay(created.id(), payKey, "tok_visa");
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.json("status").asText()).isEqualTo("PAID");
        assertThat(paid.json("paidAt").isNull()).isFalse();
        assertThat(pgPaymentRequestCount()).isEqualTo(1);

        ApiResponse fetched = getOrder(created.id());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.json("status").asText()).isEqualTo("PAID");
        assertThat(Instant.parse(fetched.json("createdAt").asText()))
                .isEqualTo(OffsetDateTime.parse(created.json("createdAt").asText()).toInstant());

        ApiResponse product = getProduct(productId);
        assertThat(product.json("stock").asInt()).isEqualTo(8);
        assertThat(product.json("reserved").asInt()).isZero();
        assertThat(product.json("available").asInt()).isEqualTo(8);

        // 멱등 재생: 같은 키/같은 요청은 PG 를 다시 부르지 않고 같은 응답
        ApiResponse replay = pay(created.id(), payKey, "tok_visa");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.rawBody()).isEqualTo(paid.rawBody());
        assertThat(pgPaymentRequestCount()).isEqualTo(1);
    }

    @Test
    void errorFormat_isProblemJson() {
        ApiResponse notFound = getProduct(Long.MAX_VALUE);
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.isProblemJson()).isTrue();
        assertThat(notFound.code()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(notFound.json("status").asInt()).isEqualTo(404);
        assertThat(notFound.body().hasNonNull("type")).isTrue();
        assertThat(notFound.body().hasNonNull("title")).isTrue();
        assertThat(notFound.body().hasNonNull("detail")).isTrue();

        ApiResponse invalid = post("/api/products", "{not json", null);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.isProblemJson()).isTrue();
        assertThat(invalid.code()).isEqualTo("VALIDATION_ERROR");

        ApiResponse badBody = createProduct("  ", 0, -1);
        assertThat(badBody.status()).isEqualTo(400);
        assertThat(badBody.isProblemJson()).isTrue();
        assertThat(badBody.code()).isEqualTo("VALIDATION_ERROR");
    }
}
