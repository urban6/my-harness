package com.example.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** order.* 설정 (01 문서 10절). ORDER_PAYMENT_TTL 은 application.yml 의 placeholder 로 매핑된다. */
@ConfigurationProperties(prefix = "order")
public record OrderProperties(
        @DefaultValue("PT15M") Duration paymentTtl,
        @DefaultValue Expiry expiry,
        @DefaultValue Idempotency idempotency) {

    public OrderProperties {
        requirePositive(paymentTtl, "order.payment-ttl");
    }

    public record Expiry(
            @DefaultValue("PT0.2S") Duration sweepInterval,
            @DefaultValue("PT10S") Duration inFlightTimeout) {
        public Expiry {
            requirePositive(sweepInterval, "order.expiry.sweep-interval");
            requirePositive(inFlightTimeout, "order.expiry.in-flight-timeout");
        }
    }

    public record Idempotency(@DefaultValue("PT30S") Duration inProgressTimeout) {
        public Idempotency {
            requirePositive(inProgressTimeout, "order.idempotency.in-progress-timeout");
        }
    }

    private static void requirePositive(Duration d, String name) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive ISO-8601 duration");
        }
    }
}
