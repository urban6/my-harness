package com.example.order;

import com.example.order.support.Api.Resp;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R3 주문 생성·조회")
class R03OrderCreateTest extends IntegrationTest {

    @Test
    @DisplayName("R3.1·R3.5 생성하면 201 + Location, PENDING_PAYMENT, 조회 결과와 같다")
    void createAndGet() {
        long p = createProduct(1200, 10);
        long q = createProduct(800, 10);

        Resp resp = createOrder("user-1", newKey(), orderBody(null, p, 2, q, 3));

        assertThat(resp.status()).isEqualTo(201);
        assertThat(resp.header("Location")).isEqualTo("/api/orders/" + resp.id());
        JsonNode order = resp.json();
        assertThat(order.path("userId").asText()).isEqualTo("user-1");
        assertThat(order.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.path("items")).hasSize(2);
        assertThat(order.path("items").get(0).path("productId").asLong()).isEqualTo(p);
        assertThat(order.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(order.path("items").get(0).path("unitPrice").asLong()).isEqualTo(1200);
        assertThat(order.path("items").get(1).path("productId").asLong()).isEqualTo(q);
        assertThat(order.path("items").get(1).path("unitPrice").asLong()).isEqualTo(800);
        assertThat(order.get("couponCode").isNull()).isTrue();
        assertThat(order.get("paidAt").isNull()).isTrue();
        assertThat(order.path("subtotal").asLong()).isEqualTo(4800);
        assertThat(order.path("discount").asLong()).isZero();
        assertThat(order.path("totalPrice").asLong()).isEqualTo(4800);
        OffsetDateTime createdAt = OffsetDateTime.parse(order.path("createdAt").asText());
        OffsetDateTime expiresAt = OffsetDateTime.parse(order.path("expiresAt").asText());
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(15));

        assertThat(order(resp.id())).isEqualTo(order);
    }

    @Test
    @DisplayName("R3.1 int 범위를 넘는 금액도 정확하다")
    void largeAmounts() {
        long p = createProduct(10_000_000, 1000);
        long q = createProduct(9_999_999, 1000);

        JsonNode order = placeOrder("u1", null, p, 1000, q, 1000).json();

        assertThat(order.path("subtotal").asLong()).isEqualTo(19_999_999_000L);
        assertThat(order.path("totalPrice").asLong()).isEqualTo(19_999_999_000L);
    }

    @Test
    @DisplayName("R3.2 헤더·본문 규칙을 어기면 400")
    void validation() {
        long p = createProduct(1000, 10);
        Map<String, Object> ok = orderBody(null, p, 1);

        assertProblem(createOrder(null, newKey(), ok), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("   ", newKey(), ok), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("u".repeat(51), newKey(), ok), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("u1", null, ok), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("u1", "k".repeat(65), ok), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("u1", newKey(), Map.of("items", List.of())), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("u1", newKey(), Map.of()), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("u1", newKey(), orderBody(null, p, 0)), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("u1", newKey(), orderBody(null, p, 1001)), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("u1", newKey(), orderBody(null, p, 1, p, 2)), 400, "VALIDATION_ERROR");
        assertProblem(createOrder("u1", newKey(), Map.of("items", List.of(Map.of("quantity", 1)))),
                400, "VALIDATION_ERROR");
        assertProblem(createOrder("u1", newKey(), Map.of("items", List.of(Map.of("productId", p)))),
                400, "VALIDATION_ERROR");
        assertProblem(api.postRaw("/api/orders", "{\"items\":[", "X-User-Id", "u1", "Idempotency-Key", newKey()),
                400, "VALIDATION_ERROR");
        assertProblem(api.postRaw("/api/orders", null, "X-User-Id", "u1", "Idempotency-Key", newKey()),
                400, "VALIDATION_ERROR");

        long[] twentyOne = new long[42];
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            ids.add(createProduct(100, 10));
        }
        for (int i = 0; i < 21; i++) {
            twentyOne[2 * i] = ids.get(i);
            twentyOne[2 * i + 1] = 1;
        }
        assertProblem(createOrder("u1", newKey(), orderBody(null, twentyOne)), 400, "VALIDATION_ERROR");
        assertThat(createOrder("u1", newKey(), orderBody(null, java.util.Arrays.copyOf(twentyOne, 40))).status())
                .isEqualTo(201);
        assertThat(createOrder("u".repeat(50), "k".repeat(64), orderBody(null, p, 1)).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R3.3 없는 상품·쿠폰은 404")
    void notFound() {
        long p = createProduct(1000, 10);
        assertProblem(placeOrder("u1", null, p, 1, 987654, 1), 404, "PRODUCT_NOT_FOUND");
        assertProblem(placeOrder("u1", "NOCOUPON", p, 1), 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.3·R3.4 재고가 부족하면 409, 예약·쿠폰 사용은 전혀 반영되지 않는다")
    void insufficientStockIsAtomic() {
        long p = createProduct(1000, 10);
        long q = createProduct(1000, 2);
        createCoupon(couponBody("ATOMIC", "FIXED", 100));

        assertProblem(placeOrder("u1", "ATOMIC", p, 5, q, 3), 409, "INSUFFICIENT_STOCK");

        assertThat(product(p).path("reserved").asInt()).isZero();
        assertThat(product(q).path("reserved").asInt()).isZero();
        assertThat(coupon("ATOMIC").path("usedCount").asInt()).isZero();
        assertThat(api.get("/api/orders").json().path("content")).isEmpty();
    }

    @Test
    @DisplayName("R3.3 available이 정확히 맞으면 허용, 예약분은 available에서 빠진다")
    void stockBoundary() {
        long p = createProduct(1000, 5);
        placeOrderOk("u1", null, p, 3);
        assertProblem(placeOrder("u2", null, p, 3), 409, "INSUFFICIENT_STOCK");
        assertThat(placeOrder("u2", null, p, 2).status()).isEqualTo(201);
        assertThat(product(p).path("available").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.4 생성 시 reserved와 쿠폰 usedCount가 늘어난다")
    void reservesStockAndCoupon() {
        long p = createProduct(1000, 10);
        long q = createProduct(500, 10);
        createCoupon(couponBody("USEIT", "FIXED", 100));

        placeOrderOk("u1", "USEIT", p, 4, q, 1);

        assertThat(product(p).path("reserved").asInt()).isEqualTo(4);
        assertThat(product(q).path("reserved").asInt()).isEqualTo(1);
        assertThat(coupon("USEIT").path("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.5 없는 주문 조회는 404")
    void orderNotFound() {
        assertProblem(api.get("/api/orders/424242"), 404, "ORDER_NOT_FOUND");
    }
}
