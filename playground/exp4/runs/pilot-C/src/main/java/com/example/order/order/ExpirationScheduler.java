package com.example.order.order;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 500ms 고정 지연 스캔. 만료 후 2초 안에 EXPIRED 가 반영되도록 한다 (R6.2). */
@Component
public class ExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpirationScheduler.class);
    private static final int BATCH = 100;
    private static final int MAX_BATCHES_PER_TICK = 10;

    private final OrderExpiryService expiryService;

    public ExpirationScheduler(OrderExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Scheduled(fixedDelayString = "${order.expiry.scan-interval:PT0.5S}")
    public void scan() {
        try {
            for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
                List<Long> ids = expiryService.findExpiredIds(BATCH);
                if (ids.isEmpty()) {
                    return;
                }
                boolean progressed = false;
                for (Long id : ids) {
                    try {
                        progressed |= expiryService.expireOne(id);
                    } catch (RuntimeException e) {
                        log.error("Failed to expire order {}", id, e);
                    }
                }
                if (!progressed || ids.size() < BATCH) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.error("Expiration scan failed", e);
        }
    }
}
