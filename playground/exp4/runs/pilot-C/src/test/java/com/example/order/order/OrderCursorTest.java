package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.order.common.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderCursorTest {

    @Test
    void roundTripPreservesMicrosecondPrecision() {
        Instant createdAt = Instant.parse("2026-10-10T01:02:03.123456Z");
        OrderCursor decoded = OrderCursor.decode(new OrderCursor(createdAt, 42).encode());
        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.id()).isEqualTo(42);
    }

    @Test
    void malformedCursorsAreRejected() {
        for (String bad : new String[] {"", "!!!", "bm90LWEtY3Vyc29y", "x".repeat(300)}) {
            assertThatThrownBy(() -> OrderCursor.decode(bad)).isInstanceOf(ApiException.class);
        }
    }
}
