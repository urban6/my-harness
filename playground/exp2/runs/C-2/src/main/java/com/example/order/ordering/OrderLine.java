package com.example.order.ordering;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** 주문 시점의 상품 가격 스냅샷. 불변. */
@Embeddable
public record OrderLine(
        @Column(name = "product_id", nullable = false) long productId,
        @Column(name = "quantity", nullable = false) int quantity,
        @Column(name = "unit_price", nullable = false) long unitPrice) {
}
