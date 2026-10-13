package com.example.order.order.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DistinctProductIdsValidator
        implements ConstraintValidator<DistinctProductIds, List<OrderItemRequest>> {

    @Override
    public boolean isValid(List<OrderItemRequest> items, ConstraintValidatorContext context) {
        if (items == null) {
            return true;
        }
        Set<Long> seen = new HashSet<>();
        for (OrderItemRequest item : items) {
            if (item == null || item.productId() == null) {
                continue;
            }
            if (!seen.add(item.productId())) {
                return false;
            }
        }
        return true;
    }
}
