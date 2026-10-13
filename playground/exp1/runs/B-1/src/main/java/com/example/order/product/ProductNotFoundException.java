package com.example.order.product;

import com.example.order.common.error.NotFoundException;

public class ProductNotFoundException extends NotFoundException {

    public ProductNotFoundException(Long id) {
        super("상품을 찾을 수 없습니다: id=" + id);
    }
}
