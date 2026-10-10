package com.example.order.config;

import java.net.URI;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from {@code order-payment.*} through the constructors (String in, Duration out).
 *
 * <p>Durations are bound as raw strings and parsed with {@link Duration#parse}, i.e. strict ISO-8601 only.
 * Binding them as {@code Duration} would use Spring Boot's lenient converter, which reads "900" as 900 ms and
 * "15m" as 15 minutes. Any invalid value fails the binding, which makes application startup fail.
 */
@ConfigurationProperties(prefix = "order-payment")
public final class OrderPaymentProperties {

    private final Duration ttl;
    private final Duration leaseDuration;
    private final Gateway gateway;
    private final Expiry expiry;

    public OrderPaymentProperties(
            @DefaultValue("PT15M") String ttl,
            @DefaultValue("PT30S") String leaseDuration,
            @DefaultValue Gateway gateway,
            @DefaultValue Expiry expiry) {
        this.ttl = isoDuration("order-payment.ttl", ttl);
        this.leaseDuration = isoDuration("order-payment.lease-duration", leaseDuration);
        if (gateway == null || expiry == null) {
            throw new IllegalArgumentException("order-payment.gateway / order-payment.expiry must not be null");
        }
        this.gateway = gateway;
        this.expiry = expiry;
        Duration minLease = gateway.connectTimeout().plus(gateway.readTimeout()).multipliedBy(2);
        if (this.leaseDuration.compareTo(minLease) < 0) {
            throw new IllegalArgumentException(
                    "order-payment.lease-duration must be >= 2 x (connect-timeout + read-timeout) = " + minLease);
        }
    }

    public Duration ttl() {
        return ttl;
    }

    public Duration leaseDuration() {
        return leaseDuration;
    }

    public Gateway gateway() {
        return gateway;
    }

    public Expiry expiry() {
        return expiry;
    }

    public static final class Gateway {

        private final String url;
        private final Duration connectTimeout;
        private final Duration readTimeout;

        public Gateway(
                @DefaultValue("http://localhost:8081") String url,
                @DefaultValue("PT2S") String connectTimeout,
                @DefaultValue("PT5S") String readTimeout) {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("order-payment.gateway.url must not be blank");
            }
            try {
                URI uri = URI.create(url);
                String scheme = uri.getScheme();
                if (!uri.isAbsolute() || uri.getHost() == null
                        || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                    throw new IllegalArgumentException("not an absolute http(s) URI");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("order-payment.gateway.url is invalid: " + e.getMessage(), e);
            }
            this.url = url;
            this.connectTimeout = isoDuration("order-payment.gateway.connect-timeout", connectTimeout);
            this.readTimeout = isoDuration("order-payment.gateway.read-timeout", readTimeout);
        }

        public String url() {
            return url;
        }

        public Duration connectTimeout() {
            return connectTimeout;
        }

        public Duration readTimeout() {
            return readTimeout;
        }
    }

    public static final class Expiry {

        private final boolean sweepEnabled;
        private final Duration sweepInterval;
        private final int batchSize;

        public Expiry(
                @DefaultValue("true") boolean sweepEnabled,
                @DefaultValue("PT10S") String sweepInterval,
                @DefaultValue("100") int batchSize) {
            this.sweepEnabled = sweepEnabled;
            this.sweepInterval = isoDuration("order-payment.expiry.sweep-interval", sweepInterval);
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("order-payment.expiry.batch-size must be within 1..1000");
            }
            this.batchSize = batchSize;
        }

        public boolean sweepEnabled() {
            return sweepEnabled;
        }

        public Duration sweepInterval() {
            return sweepInterval;
        }

        public int batchSize() {
            return batchSize;
        }
    }

    /** Strict ISO-8601 (PnDTnHnMnS) and strictly positive. */
    private static Duration isoDuration(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be an ISO-8601 duration such as PT15M");
        }
        Duration d;
        try {
            d = Duration.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be an ISO-8601 duration such as PT15M, was '" + value + "'", e);
        }
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive ISO-8601 duration");
        }
        return d;
    }
}
