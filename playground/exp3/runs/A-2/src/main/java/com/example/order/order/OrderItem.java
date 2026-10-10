package com.example.order.order;

import jakarta.persistence.Embeddable;

@Embeddable
public record OrderItem(long productId, int quantity, long unitPrice) {
}
