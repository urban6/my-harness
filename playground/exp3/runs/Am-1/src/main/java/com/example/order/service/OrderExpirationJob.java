package com.example.order.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpirationJob {

    private final OrderService orders;

    public OrderExpirationJob(OrderService orders) {
        this.orders = orders;
    }

    @Scheduled(fixedDelayString = "${app.expiration-scan-interval}")
    void run() {
        orders.expireDue();
    }
}
