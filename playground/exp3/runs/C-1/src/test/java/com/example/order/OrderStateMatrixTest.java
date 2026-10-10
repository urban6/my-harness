package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.order.order.OrderStatus;
import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.PaymentGatewayStub.Behavior;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.ResultActions;

/** R15: the complete "current status x requested action" table of 01_api_design.md section 3.3. */
class OrderStateMatrixTest extends AbstractIntegrationTest {

    private static final String OK = "200";

    /** expected outcome per (status, action): OK, or the problem slug of the 409. */
    private static final Map<OrderStatus, Map<String, String>> MATRIX = Map.of(
            OrderStatus.PENDING_PAYMENT, Map.of("pay", OK, "cancel", OK, "ship", "invalid-order-state", "deliver", "invalid-order-state"),
            OrderStatus.PAID, Map.of("pay", "invalid-order-state", "cancel", OK, "ship", OK, "deliver", "invalid-order-state"),
            OrderStatus.PAYMENT_FAILED, Map.of("pay", "invalid-order-state", "cancel", "invalid-order-state", "ship", "invalid-order-state", "deliver", "invalid-order-state"),
            OrderStatus.EXPIRED, Map.of("pay", "order-expired", "cancel", "order-expired", "ship", "invalid-order-state", "deliver", "invalid-order-state"),
            OrderStatus.CANCELLED, Map.of("pay", "invalid-order-state", "cancel", "invalid-order-state", "ship", "invalid-order-state", "deliver", "invalid-order-state"),
            OrderStatus.REFUNDED, Map.of("pay", "invalid-order-state", "cancel", "invalid-order-state", "ship", "invalid-order-state", "deliver", "invalid-order-state"),
            OrderStatus.SHIPPED, Map.of("pay", "invalid-order-state", "cancel", "invalid-order-state", "ship", "invalid-order-state", "deliver", OK),
            OrderStatus.DELIVERED, Map.of("pay", "invalid-order-state", "cancel", "invalid-order-state", "ship", "invalid-order-state", "deliver", "invalid-order-state"));

    /** status after a successful action */
    private static final Map<OrderStatus, Map<String, OrderStatus>> SUCCESS_TARGET = Map.of(
            OrderStatus.PENDING_PAYMENT, Map.of("pay", OrderStatus.PAID, "cancel", OrderStatus.CANCELLED),
            OrderStatus.PAID, Map.of("cancel", OrderStatus.REFUNDED, "ship", OrderStatus.SHIPPED),
            OrderStatus.SHIPPED, Map.of("deliver", OrderStatus.DELIVERED));

    static Stream<Arguments> everyStatusAndAction() {
        return Stream.of(OrderStatus.values()).flatMap(s ->
                Stream.of("pay", "cancel", "ship", "deliver").map(a -> Arguments.of(s, a)));
    }

    private long product;

    private long orderIn(OrderStatus target) throws Exception {
        if (product == 0) {
            product = api.createProduct("Desk", 1000, 100);
        }
        String key = "m-" + target;
        return switch (target) {
            case PENDING_PAYMENT -> api.createOrder("u", key, "MC", product, 2).get("id").asLong();
            case PAID -> api.createPaidOrder("u", key, "MC", product, 2);
            case PAYMENT_FAILED -> {
                long id = api.createOrder("u", key, "MC", product, 2).get("id").asLong();
                GATEWAY.chargeBehavior(Behavior.DECLINE);
                api.pay(id, "pay-" + key, "tok").andExpect(jsonPath("$.status").value("PAYMENT_FAILED"));
                GATEWAY.chargeBehavior(Behavior.OK);
                yield id;
            }
            case EXPIRED -> {
                long id = api.createOrder("u", key, "MC", product, 2).get("id").asLong();
                clock.advance(Duration.ofMinutes(16));
                api.getOrder(id).andExpect(jsonPath("$.status").value("EXPIRED"));
                yield id;
            }
            case CANCELLED -> {
                long id = api.createOrder("u", key, "MC", product, 2).get("id").asLong();
                api.action(id, "cancel").andExpect(status().isOk());
                yield id;
            }
            case REFUNDED -> {
                long id = api.createPaidOrder("u", key, "MC", product, 2);
                api.action(id, "cancel").andExpect(jsonPath("$.status").value("REFUNDED"));
                yield id;
            }
            case SHIPPED -> {
                long id = api.createPaidOrder("u", key, "MC", product, 2);
                api.action(id, "ship").andExpect(status().isOk());
                yield id;
            }
            case DELIVERED -> {
                long id = api.createPaidOrder("u", key, "MC", product, 2);
                api.action(id, "ship").andExpect(status().isOk());
                api.action(id, "deliver").andExpect(status().isOk());
                yield id;
            }
        };
    }

    private ResultActions perform(long orderId, String action) throws Exception {
        return "pay".equals(action) ? api.pay(orderId, "matrix-pay-key", "tok") : api.action(orderId, action);
    }

    private String statusOf(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    @ParameterizedTest(name = "R15 {0} + {1}")
    @MethodSource("everyStatusAndAction")
    @DisplayName("R15 status x action matrix")
    void everyStatusAndAction_matchesTheDesignedTable(OrderStatus status, String action) throws Exception {
        api.createCoupon("MC", "FIXED", 100, null, null, 100);
        long orderId = orderIn(status);
        assertThat(statusOf(orderId)).isEqualTo(status.name());
        int reservedBefore = reserved(product);
        int stockBefore = stock(product);
        int couponBefore = couponUsed("MC");
        int chargesBefore = GATEWAY.charges().size();
        int refundsBefore = GATEWAY.refunds().size();
        String expected = MATRIX.get(status).get(action);

        ResultActions result = perform(orderId, action);

        if (OK.equals(expected)) {
            OrderStatus target = SUCCESS_TARGET.get(status).get(action);
            result.andExpect(status().isOk()).andExpect(jsonPath("$.status").value(target.name()));
            assertThat(statusOf(orderId)).isEqualTo(target.name());
            switch (target) {
                case PAID -> {
                    assertThat(GATEWAY.charges()).hasSize(chargesBefore + 1);
                    assertThat(reserved(product)).isEqualTo(reservedBefore);
                }
                case CANCELLED -> {
                    assertThat(reserved(product)).isEqualTo(reservedBefore - 2);
                    assertThat(couponUsed("MC")).isEqualTo(couponBefore - 1);
                    assertThat(GATEWAY.refunds()).hasSize(refundsBefore);
                }
                case REFUNDED -> {
                    assertThat(GATEWAY.refunds()).hasSize(refundsBefore + 1);
                    assertThat(reserved(product)).isEqualTo(reservedBefore - 2);
                    assertThat(couponUsed("MC")).isEqualTo(couponBefore - 1);
                }
                case SHIPPED -> {
                    assertThat(stock(product)).isEqualTo(stockBefore - 2);
                    assertThat(reserved(product)).isEqualTo(reservedBefore - 2);
                }
                default -> {
                    assertThat(stock(product)).isEqualTo(stockBefore);
                    assertThat(reserved(product)).isEqualTo(reservedBefore);
                }
            }
        } else {
            result.andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:problem:order-payment:" + expected))
                    .andExpect(jsonPath("$.status").value(409));
            if ("invalid-order-state".equals(expected)) {
                result.andExpect(jsonPath("$.currentStatus").value(status.name())).andExpect(jsonPath("$.action").value(action));
            } else {
                result.andExpect(jsonPath("$.expiresAt").exists());
            }
            // a rejected request changes nothing and never reaches the PG
            assertThat(statusOf(orderId)).isEqualTo(status.name());
            assertThat(reserved(product)).isEqualTo(reservedBefore);
            assertThat(stock(product)).isEqualTo(stockBefore);
            assertThat(couponUsed("MC")).isEqualTo(couponBefore);
            assertThat(GATEWAY.charges()).hasSize(chargesBefore);
            assertThat(GATEWAY.refunds()).hasSize(refundsBefore);
        }
    }

    @Test
    @DisplayName("R15 the domain transition table allows exactly the 7 documented transitions and rejects the other 57 of 64")
    void domainTransitionTable_isExactlyTheDocumentedOne() {
        Map<OrderStatus, Set<OrderStatus>> allowed = Map.of(
                OrderStatus.PENDING_PAYMENT, EnumSet.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED, OrderStatus.EXPIRED, OrderStatus.CANCELLED),
                OrderStatus.PAID, EnumSet.of(OrderStatus.SHIPPED, OrderStatus.REFUNDED),
                OrderStatus.SHIPPED, EnumSet.of(OrderStatus.DELIVERED));
        int allowedCount = 0;
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean expected = allowed.getOrDefault(from, Set.of()).contains(to);
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(expected);
                if (expected) {
                    allowedCount++;
                } else {
                    assertThatThrownBy(() -> OrderStatus.requireTransition(from, to)).isInstanceOf(IllegalStateException.class);
                }
            }
        }
        assertThat(allowedCount).isEqualTo(7); // PENDING_PAYMENT(4) + PAID(2) + SHIPPED(1)
    }

    @ParameterizedTest(name = "R15 terminal status {0} has no outgoing transition")
    @EnumSource(value = OrderStatus.class, names = {"PAYMENT_FAILED", "EXPIRED", "CANCELLED", "REFUNDED", "DELIVERED"})
    void terminalStatuses_haveNoOutgoingTransition(OrderStatus terminal) {
        for (OrderStatus to : OrderStatus.values()) {
            assertThat(terminal.canTransitionTo(to)).isFalse();
        }
    }

    // ------------------------------------------------------------------ lease (in-progress) handling

    private void leaseOrder(long orderId, String kind, Duration validFor) {
        jdbc.update("UPDATE orders SET lease_kind = ?, lease_token = gen_random_uuid(), lease_expires_at = ? WHERE id = ?",
                kind, Timestamp.from(clock.instant().plus(validFor)), orderId);
    }

    @Test
    @DisplayName("R15 an order with an active PAY lease answers cancel and pay (other key) with 409 operation-in-progress + Retry-After")
    void activePayLease_blocksCancelAndPay() throws Exception {
        product = api.createProduct("Desk", 1000, 10);
        long orderId = api.createOrder("u", "k", null, product, 1).get("id").asLong();
        leaseOrder(orderId, "PAY", Duration.ofSeconds(30));

        api.action(orderId, "cancel").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:operation-in-progress"))
                .andExpect(jsonPath("$.operation").value("PAY"))
                .andExpect(header().string("Retry-After", "1"));
        api.pay(orderId, "pay-new", "tok").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:operation-in-progress"));
        assertThat(statusOf(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(reserved(product)).isEqualTo(1);
        assertThat(GATEWAY.requests()).isEmpty();
    }

    @Test
    @DisplayName("R15 an order with an active REFUND lease answers ship and cancel with 409 operation-in-progress")
    void activeRefundLease_blocksShipAndCancel() throws Exception {
        product = api.createProduct("Desk", 1000, 10);
        long orderId = api.createPaidOrder("u", "k", null, product, 1);
        int refundsBefore = GATEWAY.refunds().size();
        leaseOrder(orderId, "REFUND", Duration.ofSeconds(30));

        api.action(orderId, "ship").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:operation-in-progress"))
                .andExpect(jsonPath("$.operation").value("REFUND"));
        api.action(orderId, "cancel").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:operation-in-progress"));
        assertThat(statusOf(orderId)).isEqualTo("PAID");
        assertThat(stock(product)).isEqualTo(10);
        assertThat(GATEWAY.refunds()).hasSize(refundsBefore);
    }

    @Test
    @DisplayName("R15 a lease whose time has run out (crashed worker) no longer blocks anything: cancel, pay and ship proceed")
    void expiredLease_selfHeals() throws Exception {
        product = api.createProduct("Desk", 1000, 10);
        long toCancel = api.createOrder("u", "k1", null, product, 1).get("id").asLong();
        long toPay = api.createOrder("u", "k2", null, product, 1).get("id").asLong();
        long toShip = api.createPaidOrder("u", "k3", null, product, 1);
        for (long id : new long[] {toCancel, toPay, toShip}) {
            leaseOrder(id, id == toShip ? "REFUND" : "PAY", Duration.ofSeconds(-1));
        }

        api.action(toCancel, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        api.pay(toPay, "pay-heal", "tok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        api.action(toShip, "ship").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        // cancel/ship deliberately do not rewrite the (already expired) lease columns; only PAY completion clears them
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE lease_token IS NOT NULL AND lease_expires_at > ?",
                Integer.class, Timestamp.from(clock.instant()))).isZero();
        assertThat(reserved(product)).isEqualTo(1); // only the newly paid order still holds its unit (cancelled returned it, shipped confirmed it)
        assertThat(stock(product)).isEqualTo(9);
    }
}
