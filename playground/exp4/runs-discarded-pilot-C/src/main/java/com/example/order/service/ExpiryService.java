package com.example.order.service;

import com.example.order.config.AppClock;
import com.example.order.config.OrderProperties;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 결제 만료 (R6). 스케줄러와 조회/생성 경로의 지연 만료가 같은 로직을 쓴다. 주문마다 별도 짧은 트랜잭션. */
@Service
public class ExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);
    private static final int BATCH = 100;
    private static final int MAX_BATCHES = 10;

    private final OrderRepository orders;
    private final InventoryOps inventory;
    private final AppClock clock;
    private final OrderProperties props;
    private final TransactionTemplate tx;

    public ExpiryService(OrderRepository orders, InventoryOps inventory, AppClock clock, OrderProperties props,
                         PlatformTransactionManager tm) {
        this.orders = orders;
        this.inventory = inventory;
        this.clock = clock;
        this.props = props;
        this.tx = new TransactionTemplate(tm);
    }

    /** 만료 대상 주문을 모두(최대 BATCH*MAX_BATCHES) EXPIRED 로 전이. 예외는 삼키고 로그. 호출 스레드는 트랜잭션 밖이어야 한다. */
    public void expireDue() {
        try {
            for (int b = 0; b < MAX_BATCHES; b++) {
                Instant now = clock.now();
                List<Long> ids = orders.findExpirableIds(OrderStatus.PENDING_PAYMENT, now,
                        now.minus(props.expiry().inFlightTimeout()), PageRequest.of(0, BATCH));
                for (Long id : ids) {
                    try {
                        DbRetry.run(() -> tx.execute(s -> expireOne(id)));
                    } catch (RuntimeException e) {
                        log.warn("Failed to expire order {}: {}", id, e.toString());
                    }
                }
                if (ids.size() < BATCH) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Expiry sweep failed: {}", e.toString());
        }
    }

    private Boolean expireOne(long id) {
        Order order = orders.findLockedById(id).orElse(null);
        if (order == null) {
            return false;
        }
        return expireIfDue(order, clock.now());
    }

    /**
     * 주문 행 락을 쥔 상태에서 조건을 재확인하고 EXPIRED 로 전이 + 자원 복원한다.
     * PENDING_PAYMENT 이고 expiresAt 경과이며 결제 진행 표지가 없거나 오래됐을 때만. 전이했으면 true.
     */
    public boolean expireIfDue(Order order, Instant now) {
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !order.isExpiredAt(now)
                || order.isGatewayCallInFlight(now, props.expiry().inFlightTimeout())) {
            return false;
        }
        order.setStatus(OrderStatus.EXPIRED);
        order.setGatewayCallStartedAt(null);
        inventory.releaseReservation(order);
        return true;
    }
}
