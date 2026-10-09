package com.example.order.product;

import java.util.List;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long id) {
        super("상품을 찾을 수 없습니다: id=" + id);
    }

    public ProductNotFoundException(List<Long> ids) {
        super("상품을 찾을 수 없습니다: id=" + ids);
    }
}
