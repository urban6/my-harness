package com.example.order.order;

import java.util.List;

public class InsufficientStockException extends RuntimeException {

    public record Shortage(long productId, int requested, int available) {}

    private final List<Shortage> shortages;

    public InsufficientStockException(List<Shortage> shortages) {
        super("재고가 부족합니다");
        this.shortages = shortages.stream()
                .sorted(java.util.Comparator.comparingLong(Shortage::productId))
                .toList();
    }

    public List<Shortage> getShortages() {
        return shortages;
    }
}
