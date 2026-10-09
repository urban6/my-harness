package com.example.order.product;

import java.util.List;

public class ProductNotFoundException extends RuntimeException {

    private final List<Long> productIds;

    public ProductNotFoundException(Long productId) {
        this(List.of(productId));
    }

    /** @param productIds 없는 상품 id 전부 (오름차순) */
    public ProductNotFoundException(List<Long> productIds) {
        super("상품을 찾을 수 없습니다: productIds=" + productIds);
        this.productIds = List.copyOf(productIds);
    }

    public List<Long> getProductIds() {
        return productIds;
    }
}
