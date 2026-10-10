package com.example.order.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트에서 시각을 고정·이동시키는 Clock. 경계값(validFrom/validUntil, 만료) 테스트에 쓴다. */
public class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant initial) {
        this.now = initial;
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
