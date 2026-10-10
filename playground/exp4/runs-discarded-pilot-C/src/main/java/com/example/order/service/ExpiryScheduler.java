package com.example.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 만료 스위퍼: 기본 200ms (order.expiry.sweep-interval). 예외로 스케줄러가 죽지 않게 한다. */
@Component
public class ExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);

    private final ExpiryService expiry;

    public ExpiryScheduler(ExpiryService expiry) {
        this.expiry = expiry;
    }

    @Scheduled(fixedDelayString = "${order.expiry.sweep-interval:PT0.2S}")
    public void sweep() {
        try {
            expiry.expireDue();
        } catch (RuntimeException e) {
            log.warn("Expiry sweep error: {}", e.toString());
        }
    }
}
