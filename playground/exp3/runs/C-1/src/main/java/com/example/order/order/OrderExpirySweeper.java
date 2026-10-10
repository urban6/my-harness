package com.example.order.order;

import com.example.order.common.Times;
import com.example.order.config.OrderPaymentProperties;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Batch expiry. Each order is expired in its own transaction; overlapping runs/instances are harmless. */
@Component
public class OrderExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirySweeper.class);
    private static final int MAX_BATCHES_PER_RUN = 10;

    private final OrderRepository orders;
    private final OrderTransitionService transitions;
    private final OrderPaymentProperties props;
    private final Clock clock;

    public OrderExpirySweeper(OrderRepository orders, OrderTransitionService transitions,
                              OrderPaymentProperties props, Clock clock) {
        this.orders = orders;
        this.transitions = transitions;
        this.props = props;
        this.clock = clock;
    }

    /** @return number of orders this run actually expired */
    public int sweepOnce() {
        int expired = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<Long> due = orders.findDueIds(Times.now(clock), null, props.expiry().batchSize());
            for (Long id : due) {
                try {
                    if (transitions.expireIfDue(id)) {
                        expired++;
                    }
                } catch (RuntimeException e) {
                    log.error("Expiry failed for order {}", id, e);
                }
            }
            if (due.size() < props.expiry().batchSize()) {
                break;
            }
        }
        return expired;
    }
}
