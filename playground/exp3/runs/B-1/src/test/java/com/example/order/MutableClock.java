package com.example.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 만료 테스트에서 실제로 기다리지 않고 시간을 앞으로 돌리기 위한 시계. */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    public void reset() {
        now.set(Instant.now());
    }

    public void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
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
        return now.get();
    }
}
