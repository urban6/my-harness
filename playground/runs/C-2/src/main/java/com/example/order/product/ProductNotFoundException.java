package com.example.order.product;

import java.util.Collection;
import java.util.List;

public class ProductNotFoundException extends RuntimeException {

    private final List<Long> ids;

    public ProductNotFoundException(Collection<Long> ids) {
        super("상품을 찾을 수 없습니다");
        this.ids = ids.stream().sorted().toList();
    }

    public ProductNotFoundException(long id) {
        this(List.of(id));
    }

    public List<Long> getIds() {
        return ids;
    }
}
