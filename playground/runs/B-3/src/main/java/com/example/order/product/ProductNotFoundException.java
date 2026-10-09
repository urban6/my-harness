package com.example.order.product;

import java.util.Collection;

import com.example.order.common.error.ResourceNotFoundException;

public class ProductNotFoundException extends ResourceNotFoundException {

    public ProductNotFoundException(Long id) {
        super("상품을 찾을 수 없습니다: id=" + id);
    }

    public ProductNotFoundException(Collection<Long> ids) {
        super("상품을 찾을 수 없습니다: ids=" + ids);
    }
}
