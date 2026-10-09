package com.example.order.product;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class ProductNotFoundException extends BusinessException {

    public ProductNotFoundException(Long id) {
        super(ErrorCode.PRODUCT_NOT_FOUND, "상품을 찾을 수 없습니다: id=" + id);
    }
}
